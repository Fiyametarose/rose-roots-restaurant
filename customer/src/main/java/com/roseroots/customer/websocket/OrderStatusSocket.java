package com.roseroots.customer.websocket;

import com.google.gson.JsonParser;
import com.roseroots.customer.services.api.BackendAddress;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One authenticated STOMP 1.2 connection. Events invalidate the REST snapshot for their own session. */
public final class OrderStatusSocket {
    private static final String DESTINATION = "/user/queue/v1/order-status";
    private static final int MAX_FRAME = 64 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final BackendAddress address;

    public OrderStatusSocket(BackendAddress address) { this.address = address; }

    public Connection open(String token, UUID sessionId, Runnable refresh, Runnable disconnected) {
        if (token == null || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new IllegalArgumentException("Expected a bearer credential");
        }
        var listener = new Connection(token, sessionId, refresh, disconnected);
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).subprotocols("v12.stomp")
                .buildAsync(address.events(), listener).whenComplete((socket, error) -> {
                    if (error != null) listener.close();
                });
        listener.ready.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).exceptionally(error -> {
            listener.close();
            return null;
        });
        return listener;
    }

    public final class Connection implements WebSocket.Listener, OrderResynchronizer.Subscription {
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final StringBuilder pending = new StringBuilder();
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private final UUID sessionId;
        private final Runnable refresh;
        private final Runnable disconnected;
        private final java.util.concurrent.atomic.AtomicBoolean pong = new java.util.concurrent.atomic.AtomicBoolean(true);
        private String token;
        private volatile WebSocket socket;
        private boolean connected;

        private Connection(String token, UUID sessionId, Runnable refresh, Runnable disconnected) {
            this.token = token;
            this.sessionId = sessionId;
            this.refresh = refresh;
            this.disconnected = disconnected;
        }

        public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }

        @Override public void onOpen(WebSocket transport) {
            socket = transport;
            if (closed.get()) {
                transport.abort();
                return;
            }
            send("CONNECT\naccept-version:1.2\nhost:" + address.base().getHost()
                    + "\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n\n");
            token = null;
            transport.request(1);
            schedulePing();
        }

        @Override public CompletionStage<?> onText(WebSocket transport, CharSequence data, boolean last) {
            if (closed.get()) return CompletableFuture.completedFuture(null);
            try {
                pending.append(data);
                int end;
                while ((end = pending.indexOf("\0")) >= 0) {
                    if (end > MAX_FRAME) throw new IllegalArgumentException("Oversized STOMP frame");
                    var frame = pending.substring(0, end).replace("\r\n", "\n").stripLeading();
                    pending.delete(0, end + 1);
                    receive(frame);
                }
                if (pending.length() > MAX_FRAME) throw new IllegalArgumentException("Oversized STOMP frame");
                if (pending.toString().isBlank()) pending.setLength(0);
                transport.request(1);
            } catch (RuntimeException invalid) {
                close();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onBinary(WebSocket transport, java.nio.ByteBuffer data, boolean last) {
            close();
            return CompletableFuture.completedFuture(null);
        }

        private void receive(String frame) {
            int body = frame.indexOf("\n\n");
            if (body < 0) throw new IllegalArgumentException("Missing STOMP header separator");
            var header = frame.substring(0, body).split("\n");
            switch (header[0]) {
                case "CONNECTED" -> {
                    if (connected || !has(header, "version:1.2")) throw new IllegalArgumentException("Invalid STOMP handshake");
                    connected = true;
                    send("SUBSCRIBE\nid:customer-order-status\ndestination:" + DESTINATION
                            + "\nack:auto\nreceipt:orders-subscribed\n\n");
                }
                case "RECEIPT" -> {
                    if (connected && has(header, "receipt-id:orders-subscribed")) ready.complete(null);
                }
                case "MESSAGE" -> {
                    if (!connected || !has(header, "subscription:customer-order-status")) return;
                    var event = JsonParser.parseString(frame.substring(body + 2)).getAsJsonObject();
                    if (event.get("schemaVersion").getAsInt() == 1 && event.has("sessionId")
                            && sessionId.toString().equals(event.get("sessionId").getAsString())
                            && "ORDER_UPDATED".equals(event.get("type").getAsString())) refresh.run();
                }
                case "ERROR" -> close();
                default -> throw new IllegalArgumentException("Unexpected STOMP frame");
            }
        }

        private boolean has(String[] headers, String expected) {
            return java.util.Arrays.asList(headers).contains(expected);
        }

        private void send(String frame) {
            socket.sendText(frame + "\0", true).exceptionally(error -> {
                close();
                return null;
            });
        }

        private void schedulePing() {
            CompletableFuture.delayedExecutor(10, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                if (closed.get()) return;
                if (!pong.getAndSet(false)) {
                    close();
                    return;
                }
                socket.sendPing(java.nio.ByteBuffer.allocate(0)).whenComplete((ignored, error) -> {
                    if (error != null) close();
                    else schedulePing();
                });
            });
        }

        @Override public CompletionStage<?> onPong(WebSocket transport, java.nio.ByteBuffer data) {
            pong.set(true);
            transport.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override public void onError(WebSocket transport, Throwable error) { close(); }

        @Override public CompletionStage<?> onClose(WebSocket transport, int status, String reason) {
            close();
            return CompletableFuture.completedFuture(null);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (socket != null) socket.abort();
            ready.completeExceptionally(new IllegalStateException("Order updates disconnected"));
            disconnected.run();
        }
    }
}
