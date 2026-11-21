package com.roseroots.customer.services.api;

import com.google.gson.JsonObject;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.services.AssistanceService.*;
import com.roseroots.customer.services.OrderService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Customer-side polling and submission for the integrated v1 draft.2 Assistance operations. */
public final class RestAssistanceClient implements SpringBootAssistanceService.AuthenticatedClient {
    private final SpringBootOrderService rest;
    private final ScheduledExecutorService executor;

    public RestAssistanceClient(SpringBootOrderService rest, ScheduledExecutorService executor) {
        this.rest = rest;
        this.executor = executor;
    }

    @Override public boolean compatible() { return rest.available(); }

    @Override public CompletionStage<Request> submit(Command command) {
        var body = new JsonObject();
        body.addProperty("sessionId", command.context().session().toString());
        body.addProperty("type", command.type().name());
        if (command.type() == Type.OTHER && !command.message().isBlank()) body.addProperty("note", command.message());
        return rest.postAssistance(body, command.key()).handle((response, error) -> {
            if (error == null) return decode(response, command.context());
            Throwable cause = unwrap(error);
            if (cause instanceof OrderService.Problem problem && Set.of("BAD_REQUEST", "INVALID_SELECTION",
                    "SESSION_CLOSED", "SESSION_EXPIRED", "IDEMPOTENCY_CONFLICT").contains(problem.code())) {
                throw new Rejected();
            }
            throw new CompletionException(cause);
        });
    }

    @Override public Watch subscribeAndReload(Context context, Consumer<Update> listener) {
        var poll = new Poll(context, listener);
        poll.schedule(0);
        return poll;
    }

    private CompletionStage<List<Request>> read(Context context, String cursor, Set<String> cursors,
                                                  Set<String> ids, List<Request> result) {
        String path = "sessions/" + context.session() + "/assistance";
        if (cursor != null) path += "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return rest.get(path).thenCompose(page -> {
            for (var value : page.getAsJsonArray("requests")) {
                var request = decode(value.getAsJsonObject(), context);
                if (!ids.add(request.id())) throw new IllegalArgumentException("Repeated assistance request");
                result.add(request);
            }
            var next = page.get("nextCursor");
            if (next.isJsonNull()) return CompletableFuture.completedFuture(List.copyOf(result));
            String continuation = next.getAsString();
            if (continuation.isBlank() || continuation.length() > 500 || !cursors.add(continuation)) {
                throw new IllegalArgumentException("Invalid assistance cursor");
            }
            return read(context, continuation, cursors, ids, result);
        });
    }

    static Request decode(JsonObject body, Context context) {
        if (!context.session().equals(Ordering.canonicalId(body.get("sessionId").getAsString()))
                || !context.mode().name().equals(body.get("mode").getAsString())) {
            throw new IllegalArgumentException("Unexpected assistance session");
        }
        String destination = body.get("serviceDestination").getAsString();
        long version = body.get("version").getAsBigDecimal().longValueExact();
        if (destination.isBlank() || destination.length() > 60 || version < 1 || version > 9007199254740991L) {
            throw new IllegalArgumentException("Invalid assistance response");
        }
        return new Request(Ordering.canonicalId(body.get("id").getAsString()).toString(), context,
                Type.valueOf(body.get("type").getAsString()), body.get("note").getAsString(),
                Instant.parse(body.get("submittedAt").getAsString()), Instant.parse(body.get("updatedAt").getAsString()),
                Status.valueOf(body.get("status").getAsString()), version);
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private final class Poll implements Runnable, Watch {
        private final Context context;
        private final Consumer<Update> listener;
        private ScheduledFuture<?> next;
        private boolean closed;

        private Poll(Context context, Consumer<Update> listener) {
            this.context = context;
            this.listener = listener;
        }

        private synchronized void schedule(long delay) {
            if (!closed && !executor.isShutdown()) {
                try {
                    next = executor.schedule(this, delay, TimeUnit.SECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // Application shutdown raced with the next poll.
                }
            }
        }

        @Override public void run() {
            synchronized (this) {
                if (closed) return;
            }
            try {
                read(context, null, new HashSet<>(), new HashSet<>(), new ArrayList<>()).whenComplete(this::deliver);
            } catch (RuntimeException failure) {
                deliver(null, failure);
            }
        }

        private synchronized void deliver(List<Request> requests, Throwable failure) {
            if (closed) return;
            if (failure == null) {
                listener.accept(new Update(Connection.LIVE, requests));
            } else {
                Throwable cause = unwrap(failure);
                boolean disconnected = cause instanceof OrderService.Problem problem
                        && Set.of("CONNECTION_INTERRUPTED", "SERVICE_UNAVAILABLE").contains(problem.code());
                listener.accept(new Update(disconnected ? Connection.DISCONNECTED : Connection.ERROR, List.of()));
            }
            schedule(3);
        }

        @Override public synchronized void close() {
            closed = true;
            if (next != null) next.cancel(false);
        }
    }
}
