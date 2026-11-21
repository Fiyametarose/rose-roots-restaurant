package com.roseroots.customer.services.api;

import com.google.gson.JsonObject;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static com.roseroots.customer.models.Ordering.canonicalId;

public final class RestAssistanceClient implements SpringBootAssistanceService.AuthenticatedClient {
    private final SpringBootOrderService rest;
    private final ScheduledExecutorService executor;

    public RestAssistanceClient(SpringBootOrderService rest, ScheduledExecutorService executor) {
        this.rest = rest;
        this.executor = executor;
    }

    @Override public boolean compatible() { return rest.available(); }

    @Override public CompletionStage<Request> submit(Command command) {
        var payload = new JsonObject();
        payload.addProperty("sessionId", command.context().session().toString());
        payload.addProperty("type", command.type().name());
        if (command.type() == Type.OTHER) payload.addProperty("note", command.message());
        return rest.submitAssistance(payload, command.key()).handle((reply, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof OrderService.Problem problem && Set.of("BAD_REQUEST", "INVALID_SELECTION",
                        "SESSION_EXPIRED", "SESSION_CLOSED", "IDEMPOTENCY_CONFLICT").contains(problem.code())) {
                    throw new Rejected();
                }
                throw new CompletionException(cause);
            }
            Request request = read(command.context(), reply);
            if (request.status() != Status.SUBMITTED) throw invalid();
            return request;
        });
    }

    @Override public Watch subscribeAndReload(Context context, Consumer<Update> listener) {
        var closed = new AtomicBoolean();
        class Poll implements Runnable {
            private int failures;
            @Override public void run() {
                if (closed.get() || executor.isShutdown()) return;
                try {
                    page(context, null, new ArrayList<>(), new HashSet<>(), new HashSet<>())
                            .whenComplete(this::completed);
                } catch (RuntimeException failure) {
                    completed(null, failure);
                }
            }
            private void completed(List<Request> requests, Throwable failure) {
                if (closed.get() || executor.isShutdown()) return;
                failures = failure == null ? 0 : Math.min(4, failures + 1);
                listener.accept(new Update(failure == null ? Connection.LIVE : Connection.ERROR,
                        failure == null ? requests : List.of()));
                try {
                    executor.schedule(this, Math.min(30, 3L << failures), TimeUnit.SECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // The application is closing.
                }
            }
        }
        listener.accept(new Update(Connection.LOADING, List.of()));
        executor.execute(new Poll());
        return () -> closed.set(true);
    }

    private CompletionStage<List<Request>> page(Context context, String cursor, List<Request> rows,
                                                 Set<String> visited, Set<String> ids) {
        String suffix = cursor == null ? "" : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return rest.get("sessions/" + context.session() + "/assistance" + suffix).thenCompose(body -> {
            for (var entry : body.getAsJsonArray("requests")) {
                Request request = read(context, entry.getAsJsonObject());
                if (!ids.add(request.id())) throw invalid();
                rows.add(request);
            }
            var next = body.get("nextCursor");
            if (next.isJsonNull()) return CompletableFuture.completedFuture(List.copyOf(rows));
            String token = next.getAsString();
            if (token.isBlank() || token.length() > 500 || !visited.add(token) || visited.size() > 2000) throw invalid();
            return page(context, token, rows, visited, ids);
        });
    }

    private static Request read(Context context, JsonObject row) {
        if (!context.session().equals(canonicalId(row.get("sessionId").getAsString()))
                || !context.mode().name().equals(row.get("mode").getAsString())) throw invalid();
        String destination = context.mode().name().equals("DINE_IN") ? "Table " + context.destination() : context.destination();
        if (!destination.equals(row.get("serviceDestination").getAsString())) throw invalid();
        var status = Status.valueOf(row.get("status").getAsString());
        if (row.getAsJsonPrimitive("version").getAsBigDecimal().longValueExact() != status.ordinal() + 1L) throw invalid();
        return new Request(canonicalId(row.get("id").getAsString()).toString(), context,
                Type.valueOf(row.get("type").getAsString()), row.get("note").getAsString(),
                Instant.parse(row.get("submittedAt").getAsString()), Instant.parse(row.get("updatedAt").getAsString()), status);
    }

    private static OrderService.Problem invalid() { return new OrderService.Problem("INVALID_RESPONSE", false); }
}
