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
    public enum State { CONNECTING, DISCONNECTED, ERROR }

    private final Consumer<Boolean> online;
    private final Consumer<State> status;
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
        this(executor, sessionId, updates, read, publish, online, ignored -> {});
    }

    public OrderResynchronizer(ScheduledExecutorService executor, UUID sessionId, Updates updates,
                               Function<UUID, CompletionStage<T>> read, Consumer<T> publish, Consumer<Boolean> online,
                               Consumer<State> status) {
        this.executor = executor;
        this.sessionId = sessionId;
        this.updates = updates;
        this.read = read;
        this.publish = publish;
        this.online = online;
        this.status = status;
        dispatch(this::connect);
    }

    private void connect() {
        if (stopped || connection != null) return;
        int current = ++generation;
        status.accept(State.CONNECTING);
        try {
            connection = updates.open(() -> dispatch(() -> invalidate(current)),
                    () -> dispatch(() -> lost(current, false)));
            connection.ready().whenComplete((ignored, error) -> dispatch(() -> {
                if (!current(current)) return;
                if (error != null) {
                    lost(current, false);
                } else {
                    ready = true;
                    invalidate(current);
                    probe = executor.scheduleWithFixedDelay(() -> invalidate(current), 15, 15, TimeUnit.SECONDS);
                }
            }));
        } catch (RuntimeException error) {
            lost(current, false);
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
            read.apply(sessionId).whenComplete((value, error) -> dispatch(() -> {
                if (!current(current)) return;
                fetching = false;
                if (error != null || value == null) {
                    lost(current, true);
                } else if (dirty) {
                    invalidate(current);
                } else {
                    backoff = 1;
                    publish.accept(value);
                    online.accept(true);
                }
            }));
        } catch (RuntimeException error) {
            lost(current, true);
        }
    }

    private void lost(int current, boolean readFailed) {
        if (!current(current)) return;
        disconnect();
        online.accept(false);
        status.accept(readFailed ? State.ERROR : State.DISCONNECTED);
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

    private void dispatch(Runnable action) {
        try {
            executor.execute(action);
        } catch (java.util.concurrent.RejectedExecutionException stoppedExecutor) {
            if (!executor.isShutdown()) throw stoppedExecutor;
        }
    }

    @Override public void close() {
        dispatch(() -> {
            stopped = true;
            if (reconnect != null) reconnect.cancel(false);
            disconnect();
        });
    }
}
