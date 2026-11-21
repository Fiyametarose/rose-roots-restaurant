package com.roseroots.kitchen.service.http;

import com.google.gson.JsonObject;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Item;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.service.KitchenService;
import com.roseroots.kitchen.service.KitchenSync;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class SpringBootKitchenService implements KitchenService, KitchenSync {
    private static final String ORDERS = "/api/v1/kitchen/orders?limit=50";
    private final URI origin;
    private final StaffCredentialProvider credentials;
    private final HttpClient client;
    private final Duration timeout;
    private final Consumer<Runnable> dispatcher;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, Long> handles = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong();
    private final KitchenTicketJson json = new KitchenTicketJson(id ->
            handles.computeIfAbsent(id, key -> nextHandle.incrementAndGet()));
    private volatile List<Ticket> tickets = List.of();
    private volatile Status status = Status.OFFLINE;
    private volatile String message = "";
    private volatile boolean busy;

    public SpringBootKitchenService(KitchenBackendConfig config, StaffCredentialProvider credentials,
                                    Consumer<Runnable> dispatcher) {
        this(config.origin(), credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(12), dispatcher);
        if (config.mode() != KitchenBackendConfig.Mode.CONNECTED
                || config.origin() == null || !"https".equalsIgnoreCase(config.origin().getScheme())) {
            throw new IllegalArgumentException("Connected Kitchen mode requires HTTPS.");
        }
    }

    // Package-private transport injection for controlled HTTP fixtures.
    SpringBootKitchenService(URI origin, StaffCredentialProvider credentials, HttpClient client,
                             Duration timeout, Consumer<Runnable> dispatcher) {
        this.origin = origin;
        this.credentials = credentials;
        this.client = client;
        this.timeout = timeout;
        this.dispatcher = dispatcher;
    }

    @Override public boolean local() { return false; }
    @Override public Status status() { return status; }
    @Override public String message() { return message; }
    @Override public boolean busy() { return busy; }
    @Override public boolean writesAvailable() { return status == Status.CONNECTED && !busy; }
    @Override public List<Ticket> activeTickets() { return tickets; }

    @Override
    public List<Ticket> historyTickets() {
        return List.of();
    }

    @Override
    public Runnable onOrdersChanged(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public synchronized void refresh() {
        if (busy) {
            return;
        }
        begin(Status.LOADING, "Loading the active ticket snapshot…");
        fetchSnapshot().whenComplete((snapshot, error) -> {
            if (error == null) {
                tickets = snapshot;
                finish(Status.CONNECTED, "");
            } else {
                Failure failure = failure(error);
                finish(failure.offline() ? Status.OFFLINE : Status.FAILED, failure.getMessage());
            }
        });
    }

    @Override
    public synchronized void advanceItem(long ticketId, long itemId, Ticket.Status expected) {
        if (!writesAvailable()) {
            return;
        }
        Ticket ticket = tickets.stream().filter(order -> order.id() == ticketId).findFirst().orElse(null);
        Item item = ticket == null ? null : ticket.items().stream().filter(line -> line.id() == itemId).findFirst().orElse(null);
        if (item == null || item.status() != expected || !item.actionable()) {
            message = "This item is no longer eligible. Review the current ticket.";
            notifyListeners();
            return;
        }
        Ticket.Status target = expected.next();
        JsonObject body = new JsonObject();
        body.addProperty("status", target.name());
        body.addProperty("expectedVersion", item.server().version());
        begin(Status.CONNECTED, "Saving item status…");
        send("/api/v1/kitchen/items/" + item.server().id() + "/status", body)
                .thenApply(raw -> {
                    Ticket updated = parse(() -> json.ticket(KitchenTicketJson.object(raw)));
                    if (!updated.server().id().equals(ticket.server().id())
                            || updated.server().version() <= ticket.server().version()
                            || !confirmed(updated, item, target)) {
                        throw unreadable();
                    }
                    return updated;
                })
                .whenComplete((updated, error) -> {
                    if (error == null) {
                        tickets = tickets.stream().map(entry -> entry.id() == ticketId ? updated : entry)
                                .filter(entry -> Ticket.Status.LANES.contains(entry.status())).toList();
                        finish(Status.CONNECTED, "Item status saved.");
                    } else {
                        handleWriteFailure(ticketId, item, target, failure(error));
                    }
                });
    }

    @Override
    public void advance(long ticketId, Station station, Ticket.Status expected) {
        throw new UnsupportedOperationException("Connected preparation updates one item at a time.");
    }

    @Override
    public void changeRush(long ticketId, boolean enabled, String reason) {
        throw new UnsupportedOperationException("Rush changes are unavailable in connected mode.");
    }

    private void handleWriteFailure(long ticketId, Item item, Ticket.Status target, Failure failure) {
        if (failure.httpStatus != 409 && !failure.uncertain()) {
            boolean security = failure.httpStatus == 401 || failure.httpStatus == 403;
            finish(security ? Status.FAILED : Status.CONNECTED, failure.getMessage());
            return;
        }
        status = Status.LOADING;
        message = failure.getMessage() + " Reloading authoritative tickets…";
        notifyListeners();
        fetchSnapshot().whenComplete((snapshot, error) -> {
            if (error == null) {
                tickets = snapshot;
                boolean persisted = snapshot.stream().anyMatch(ticket ->
                        ticket.id() == ticketId && confirmed(ticket, item, target));
                String result = persisted ? "Current server state confirms the requested status."
                        : "Current state reloaded. Review the ticket before taking another action.";
                finish(Status.CONNECTED, failure.getMessage() + " " + result);
            } else {
                Failure reload = failure(error);
                finish(reload.offline() ? Status.OFFLINE : Status.FAILED,
                        "Update could not be reconciled. Refresh tickets before taking another action. " + reload.getMessage());
            }
        });
    }

    private static boolean confirmed(Ticket ticket, Item previous, Ticket.Status target) {
        return ticket.items().stream().anyMatch(item -> item.server().id().equals(previous.server().id())
                && item.server().version() > previous.server().version() && item.status() == target);
    }

    private CompletableFuture<List<Ticket>> fetchSnapshot() {
        return fetchPage(null, new ArrayList<>(), new HashSet<>(), new HashSet<>());
    }

    private CompletableFuture<List<Ticket>> fetchPage(String cursor, List<Ticket> complete,
                                                      HashSet<String> cursors, HashSet<UUID> ids) {
        String suffix = cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return send(ORDERS + suffix, null).thenCompose(raw -> {
            KitchenTicketJson.Page page = parse(() -> json.readPage(raw));
            for (Ticket ticket : page.tickets()) {
                if (!ids.add(ticket.server().id()) || !Ticket.Status.LANES.contains(ticket.status())) {
                    throw unreadable();
                }
                complete.add(ticket);
            }
            String next = page.nextCursor();
            if (next == null) {
                return CompletableFuture.completedFuture(List.copyOf(complete));
            }
            if (next.isBlank() || next.length() > 500 || !cursors.add(next) || cursors.size() > 2000) {
                throw unreadable();
            }
            return fetchPage(next, complete, cursors, ids);
        });
    }

    private CompletableFuture<String> send(String path, JsonObject body) {
        try {
            String token = credentials.bearerToken().orElse("");
            if (token.isBlank() || !token.matches("[A-Za-z0-9._~+/\\-]+=*")) {
                return CompletableFuture.failedFuture(new Failure(401, ""));
            }
            var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json, application/problem+json");
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()));
            }
            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> {
                        if (error != null) {
                            throw new Failure(0, "");
                        }
                        if (response.statusCode() != 200) {
                            String code = "";
                            try {
                                code = KitchenTicketJson.object(response.body()).get("code").getAsString();
                            } catch (RuntimeException ignored) {
                                // HTTP status remains useful when a Problem body is absent.
                            }
                            throw new Failure(response.statusCode(), code);
                        }
                        return response.body();
                    });
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(new Failure(401, ""));
        }
    }

    private static <T> T parse(java.util.function.Supplier<T> reader) {
        try {
            return reader.get();
        } catch (RuntimeException ex) {
            throw unreadable();
        }
    }

    private static Failure unreadable() {
        return new Failure(-1, "");
    }

    private static Failure failure(Throwable error) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        return cause instanceof Failure failure ? failure : unreadable();
    }

    private void begin(Status next, String detail) {
        busy = true;
        status = next;
        message = detail;
        notifyListeners();
    }

    private synchronized void finish(Status next, String detail) {
        status = next;
        message = detail;
        busy = false;
        notifyListeners();
    }

    private void notifyListeners() {
        dispatcher.accept(() -> listeners.forEach(Runnable::run));
    }

    private static final class Failure extends RuntimeException {
        private final int httpStatus;

        private Failure(int status, String code) {
            super(switch (status) {
                case -1 -> "The server returned an unexpected ticket response.";
                case 0 -> "The server could not be reached or timed out.";
                case 401 -> "Staff authentication is required or expired.";
                case 403 -> "The server denied staff access.";
                case 409, 422 -> switch (code) {
                    case "REVIEW_REQUIRED" -> "Allergy review is required before preparation.";
                    case "INVALID_TRANSITION" -> "The server rejected the preparation transition.";
                    case "VERSION_CONFLICT" -> "The ticket version changed.";
                    default -> "The server rejected the current item state.";
                };
                case 429 -> "The server is rate limiting requests.";
                default -> "The server rejected or could not complete the request (HTTP " + status + ").";
            });
            httpStatus = status;
        }

        boolean offline() { return httpStatus == 0; }
        boolean uncertain() { return httpStatus <= 0 || httpStatus >= 500; }
    }
}
