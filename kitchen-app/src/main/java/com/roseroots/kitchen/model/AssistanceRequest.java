package com.roseroots.kitchen.model;

import java.time.OffsetDateTime;

public record AssistanceRequest(long id, String reference, Type type, OffsetDateTime createdAt,
                                Action acknowledged, Action resolved) {
    public enum Type {
        WAITER("Call Waiter"), DRINKING_WATER("Drinking Water"), NAPKINS("Extra Napkins"),
        CUTLERY("Extra Cutlery"), BILL_HELP("Bill Help"), ORDER_HELP("Order Help"), OTHER("Other Assistance");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    public enum Status {
        PENDING("Pending"), IN_PROGRESS("In progress"), RESOLVED("Resolved");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    public record Action(String staffId, OffsetDateTime at) {}

    public Status status() {
        if (resolved != null) {
            return Status.RESOLVED;
        }
        return acknowledged == null ? Status.PENDING : Status.IN_PROGRESS;
    }
}
