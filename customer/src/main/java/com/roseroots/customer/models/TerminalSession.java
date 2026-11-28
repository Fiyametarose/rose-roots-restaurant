package com.roseroots.customer.models;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record TerminalSession(String sessionId, TerminalDevice device, ServiceMode mode,
                              Status status, String pickupReference, List<SessionOrder> orders, String draftId) {
    public TerminalSession(String sessionId, TerminalDevice device, ServiceMode mode,
                           Status status, String pickupReference, List<SessionOrder> orders) {
        this(sessionId, device, mode, status, pickupReference, orders, null);
    }
    public TerminalSession {
        orders = List.copyOf(orders);
        if (status == Status.LOCAL_DRAFT) {
            if (draftId == null || draftId.isBlank() || sessionId != null || pickupReference != null || !orders.isEmpty()) {
                throw new IllegalArgumentException("A local draft cannot contain an authoritative session or orders");
            }
        } else if (draftId != null) {
            throw new IllegalArgumentException("Only a local draft has a draft ID");
        }
        if (orders.stream().anyMatch(order -> !Objects.equals(sessionId, order.sessionId()))) {
            throw new IllegalArgumentException("An order belongs to another session");
        }
        if (status == Status.OPEN && (sessionId == null || sessionId.isBlank()
                || (mode == ServiceMode.DINE_IN && device.table() == null)
                || (mode == ServiceMode.TAKEAWAY && (pickupReference == null || pickupReference.isBlank())))) {
            throw new IllegalArgumentException("An open session requires an ID and a service destination");
        }
    }

    public enum Status { OPEN, LOCAL_DRAFT, CLOSED, UNASSIGNED, UNAVAILABLE }

    public boolean allowsCart() {
        return isOpen() || status == Status.LOCAL_DRAFT;
    }

    public String cartContextId() {
        return status == Status.LOCAL_DRAFT ? "draft:" + draftId : sessionId == null ? null : "session:" + sessionId;
    }

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
