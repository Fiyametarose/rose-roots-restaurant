package com.roseroots.customer.services;

import com.roseroots.customer.models.OrderHistory;
import java.util.UUID;
import java.util.function.Consumer;

@FunctionalInterface
public interface OrderTrackingService {
    enum State { LOADING, LIVE, DISCONNECTED, RECONNECTING, ERROR }

    record Update(State state, OrderHistory history) {}

    @FunctionalInterface interface Watch extends AutoCloseable {
        @Override void close();
    }

    Watch watch(UUID session, Consumer<Update> listener);

    static OrderTrackingService unavailable() {
        return (session, listener) -> {
            listener.accept(new Update(State.DISCONNECTED, null));
            return () -> {};
        };
    }
}
