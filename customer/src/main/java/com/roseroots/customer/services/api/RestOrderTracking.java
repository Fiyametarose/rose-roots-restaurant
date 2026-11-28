package com.roseroots.customer.services.api;

import com.roseroots.customer.services.OrderTrackingService;
import com.roseroots.customer.services.OrderService;
import com.roseroots.customer.models.OrderHistory;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.WaitEstimate;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RestOrderTracking implements OrderTrackingService {
    private final SpringBootOrderService orders;
    private final ScheduledExecutorService executor;
    private record CachedEstimate(Ordering.Accepted order, Optional<WaitEstimate> estimate, Instant refreshAt) {}

    public RestOrderTracking(SpringBootOrderService orders, ScheduledExecutorService executor) {
        this.orders = orders;
        this.executor = executor;
    }

    @Override public Watch watch(UUID session, Consumer<Update> listener) {
        var closed = new AtomicBoolean();
        class Poll implements Runnable {
            private final Map<UUID, CachedEstimate> estimates = new HashMap<>();

            @Override public void run() {
                if (closed.get()) return;
                try {
                    orders.snapshot(session).thenCompose(this::withEstimates).whenComplete((history, failure) -> {
                        if (failure == null) deliver(new Update(State.LIVE, history));
                        else failed(failure);
                    });
                } catch (RuntimeException failure) {
                    failed(failure);
                }
            }

            private CompletionStage<OrderHistory> withEstimates(OrderHistory history) {
                if (closed.get()) return CompletableFuture.completedFuture(history);
                var active = history.orders().stream().filter(order -> order.status() == Ordering.Status.RECEIVED
                        || order.status() == Ordering.Status.PREPARING).toList();
                var ids = active.stream().map(Ordering.Accepted::id).collect(java.util.stream.Collectors.toSet());
                estimates.keySet().retainAll(ids);
                Instant now = Instant.now();
                var reads = active.stream().filter(order -> {
                    var last = estimates.get(order.id());
                    return last == null || !last.order().equals(order) || !last.refreshAt().isAfter(now);
                }).limit(8).map(order -> orders.estimateOrder(order.id()).toCompletableFuture().orTimeout(2, TimeUnit.SECONDS)
                        .handle((value, failure) -> {
                            var ready = failure == null ? value.filter(estimate -> estimate.isCurrent(Instant.now())) : Optional.<WaitEstimate>empty();
                            return Map.entry(order.id(), new CachedEstimate(order, ready,
                                    ready.map(WaitEstimate::validUntil).orElseGet(() -> Instant.now().plusSeconds(15))));
                        })).toList();
                return CompletableFuture.allOf(reads.toArray(CompletableFuture<?>[]::new)).thenApply(ignored -> {
                    reads.forEach(read -> { var entry = read.join(); estimates.put(entry.getKey(), entry.getValue()); });
                    var displayed = new HashMap<UUID, WaitEstimate>();
                    var displayedAt = Instant.now();
                    for (var order : active) {
                        var reading = estimates.get(order.id());
                        if (reading != null && reading.order().equals(order)) {
                            reading.estimate().filter(estimate -> estimate.isCurrent(displayedAt))
                                    .ifPresent(estimate -> displayed.put(order.id(), estimate));
                        }
                    }
                    return new OrderHistory(history.sessionId(), history.orders(), history.orderTotals(), history.totals(), displayed);
                });
            }

            private void failed(Throwable failure) {
                while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
                    failure = failure.getCause();
                }
                State state = failure instanceof OrderService.Problem problem
                        && ("CONNECTION_INTERRUPTED".equals(problem.code()) || "SERVICE_UNAVAILABLE".equals(problem.code()))
                        ? State.DISCONNECTED : State.ERROR;
                deliver(new Update(state, null));
            }

            private void deliver(Update update) {
                if (closed.get() || executor.isShutdown()) return;
                listener.accept(update);
                try {
                    executor.schedule(this, 3, TimeUnit.SECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // The application is closing.
                }
            }
        }
        listener.accept(new Update(State.LOADING, null));
        executor.execute(new Poll());
        return () -> closed.set(true);
    }
}
