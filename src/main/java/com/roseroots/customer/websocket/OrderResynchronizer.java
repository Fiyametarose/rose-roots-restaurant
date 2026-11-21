package com.roseroots.customer.websocket;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Runs on a caller-owned, single-thread scheduled executor. Close it when the session changes. */
public final class OrderResynchronizer<T> implements AutoCloseable {
    public interface Subscription extends AutoCloseable {
        CompletionStage<Void> ready();
        @Override void close();
    }

    @FunctionalInterface public interface Updates {
        Subscription open(Runnable changed, Runnable disconnected);
    }

    private final ScheduledExecutorService executor;
    private final UUID sessionId;
    private final Updates updates;
    private final Function<UUID, CompletionStage<T>> read;
    private final Consumer<T> publish;
    private final Consumer<Boolean> online;
    private Subscription connection;
    private java.util.concurrent.ScheduledFuture<?> reconnect;
    private java.util.concurrent.ScheduledFuture<?> probe;
    private int generation;
    private int backoff = 1;
    private boolean stopped;
    private boolean ready;
    private boolean fetching;
    private boolean dirty;

    public OrderResynchronizer(ScheduledExecutorService executor, UUID sessionId, Updates updates,
                               Function<UUID, CompletionStage<T>> read, Consumer<T> publish, Consumer<Boolean> online) {
        this.executor = executor;
        this.sessionId = sessionId;
        this.updates = updates;
        this.read = read;
        this.publish = publish;
        this.online = online;
        executor.execute(this::connect);
    }

    private void connect() {
        if (stopped || connection != null) return;
        int current = ++generation;
        try {
            connection = updates.open(() -> executor.execute(() -> invalidate(current)),
                    () -> executor.execute(() -> lost(current)));
            connection.ready().whenComplete((ignored, error) -> executor.execute(() -> {
                if (!current(current)) return;
                if (error != null) {
                    lost(current);
                } else {
                    ready = true;
                    invalidate(current);
                    probe = executor.scheduleWithFixedDelay(() -> invalidate(current), 15, 15, TimeUnit.SECONDS);
                }
            }));
        } catch (RuntimeException error) {
            lost(current);
        }
    }

    private void invalidate(int current) {
        if (!current(current) || !ready) return;
        if (fetching) {
            dirty = true;
            return;
        }
        fetching = true;
        dirty = false;
        try {
            read.apply(sessionId).whenComplete((value, error) -> executor.execute(() -> {
                if (!current(current)) return;
                fetching = false;
                if (error != null || value == null) {
                    lost(current);
                } else if (dirty) {
                    invalidate(current);
                } else {
                    backoff = 1;
                    publish.accept(value);
                    online.accept(true);
                }
            }));
        } catch (RuntimeException error) {
            lost(current);
        }
    }

    private void lost(int current) {
        if (!current(current)) return;
        disconnect();
        online.accept(false);
        reconnect = executor.schedule(this::connect, backoff, TimeUnit.SECONDS);
        backoff = Math.min(30, backoff * 2);
    }

    private boolean current(int value) { return !stopped && generation == value; }

    private void disconnect() {
        generation++;
        fetching = ready = dirty = false;
        if (probe != null) probe.cancel(false);
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override public void close() {
        executor.execute(() -> {
            stopped = true;
            if (reconnect != null) reconnect.cancel(false);
            disconnect();
        });
    }
}
