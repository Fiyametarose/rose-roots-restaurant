package com.roseroots.customer.services.api;

import com.roseroots.customer.services.OrderTrackingService;
import com.roseroots.customer.services.OrderService;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RestOrderTracking implements OrderTrackingService {
    private final SpringBootOrderService orders;
    private final ScheduledExecutorService executor;

    public RestOrderTracking(SpringBootOrderService orders, ScheduledExecutorService executor) {
        this.orders = orders;
        this.executor = executor;
    }

    @Override public Watch watch(UUID session, Consumer<Update> listener) {
        var closed = new AtomicBoolean();
        class Poll implements Runnable {
            @Override public void run() {
                if (closed.get()) return;
                try {
                    orders.snapshot(session).whenComplete((history, failure) -> {
                        if (failure == null) deliver(new Update(State.LIVE, history));
                        else failed(failure);
                    });
                } catch (RuntimeException failure) {
                    failed(failure);
                }
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
