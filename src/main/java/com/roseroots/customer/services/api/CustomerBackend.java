package com.roseroots.customer.services.api;

import com.roseroots.customer.models.OrderHistory;
import com.roseroots.customer.services.OrderTrackingService;
import com.roseroots.customer.websocket.OrderResynchronizer;
import com.roseroots.customer.websocket.OrderStatusSocket;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
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

    public OrderTrackingService tracking(ScheduledExecutorService executor) {
        return (session, listener) -> {
            if (!orders.available()) return OrderTrackingService.unavailable().watch(session, listener);
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            var watcher = new OrderResynchronizer<OrderHistory>(executor, session,
                    (refresh, disconnected) -> socket.open(credential.get(), session, refresh, disconnected),
                    orders::snapshot, history -> listener.accept(new OrderTrackingService.Update(
                            OrderTrackingService.State.LIVE, history)), ignored -> {},
                    status -> listener.accept(new OrderTrackingService.Update(switch (status) {
                        case CONNECTING -> first.getAndSet(false)
                                ? OrderTrackingService.State.LOADING : OrderTrackingService.State.RECONNECTING;
                        case DISCONNECTED -> OrderTrackingService.State.DISCONNECTED;
                        case ERROR -> OrderTrackingService.State.ERROR;
                    }, null)));
            return watcher::close;
        };
    }
}
