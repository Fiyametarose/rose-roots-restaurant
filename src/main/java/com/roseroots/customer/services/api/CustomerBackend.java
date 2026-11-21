package com.roseroots.customer.services.api;

import com.roseroots.customer.websocket.OrderResynchronizer;
import com.roseroots.customer.websocket.OrderStatusSocket;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared PC 2 address and authentication for Customer REST and order updates. */
public final class CustomerBackend {
    private final SpringBootOrderService orders;
    private final OrderStatusSocket socket;
    private final Supplier<String> credential;

    public CustomerBackend(BackendAddress address, Supplier<String> credential, BooleanSupplier compatible) {
        orders = new SpringBootOrderService(address, credential, compatible);
        socket = new OrderStatusSocket(address);
        this.credential = credential;
    }

    public SpringBootOrderService orders() { return orders; }

    public OrderResynchronizer<SpringBootOrderService.SessionSnapshot> watchOrders(
            UUID sessionId, ScheduledExecutorService executor,
            Consumer<SpringBootOrderService.SessionSnapshot> publish, Consumer<Boolean> online) {
        return new OrderResynchronizer<>(executor, sessionId, (refresh, disconnected) -> {
            if (!orders.available()) throw new IllegalStateException("Backend compatibility has not been established");
            return socket.open(credential.get(), sessionId, refresh, disconnected);
        }, orders::snapshot, publish, online);
    }
}
