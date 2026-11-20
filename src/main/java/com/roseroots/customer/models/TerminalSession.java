package com.roseroots.customer.models;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record TerminalSession(String sessionId, TerminalDevice device, ServiceMode mode,
                              Status status, String pickupReference, List<SessionOrder> orders) {
    public TerminalSession {
        orders = List.copyOf(orders);
        if (orders.stream().anyMatch(order -> !Objects.equals(sessionId, order.sessionId()))) {
            throw new IllegalArgumentException("An order belongs to another session");
        }
        if (status == Status.OPEN && (sessionId == null || sessionId.isBlank()
                || (mode == ServiceMode.DINE_IN && device.table() == null)
                || (mode == ServiceMode.TAKEAWAY && (pickupReference == null || pickupReference.isBlank())))) {
            throw new IllegalArgumentException("An open session requires an ID and a service destination");
        }
    }

    public enum Status { OPEN, CLOSED, UNASSIGNED, UNAVAILABLE }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public boolean canSubmitOrders() {
        return isOpen() && !device.demo();
    }

    public BigDecimal runningBill() {
        return orders.stream().map(SessionOrder::confirmedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
