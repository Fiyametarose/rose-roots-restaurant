package com.roseroots.kitchen.model;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Ticket(long id, Type type, String destination, OffsetDateTime orderedAt,
                     Duration delayTarget, List<RushChange> rushHistory, List<Item> items, OrderCompletion completion, ServerOrder server) {
    public Ticket(long id, Type type, String destination, OffsetDateTime orderedAt,
                  Duration delayTarget, List<RushChange> rushHistory, List<Item> items) {
        this(id, type, destination, orderedAt, delayTarget, rushHistory, items,
                !items.isEmpty() && statusOf(items) == Status.READY
                        ? new OrderCompletion(OrderCompletion.Status.READY, null) : null, null);
    }

    public Ticket(long id, Type type, String destination, OffsetDateTime orderedAt,
                  Duration delayTarget, List<RushChange> rushHistory, List<Item> items, OrderCompletion completion) {
        this(id, type, destination, orderedAt, delayTarget, rushHistory, items, completion, null);
    }

    public record ServerOrder(UUID id, UUID sessionId, long version, Status status, boolean rush, boolean delayed,
                              String visitAllergy) {}

    public record ServerItem(UUID id, long version, String allergyReviewStatus, boolean unresolvedRisk) {
        public boolean cleared() {
            return !unresolvedRisk && ("NOT_REQUIRED".equals(allergyReviewStatus)
                    || "APPROVED_FOR_PREPARATION".equals(allergyReviewStatus));
        }
    }

    public Ticket {
        items = List.copyOf(items);
        rushHistory = List.copyOf(rushHistory);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A ticket needs at least one item");
        }
        if (completion != null) {
            switch (completion.status()) {
                case READY -> requirePrepared(items, server);
                case SERVED -> {
                    requirePrepared(items, server);
                    if (type != Type.DINE_IN) {
                        throw new IllegalArgumentException("Only dine-in orders can be served.");
                    }
                }
                case COLLECTED -> {
                    requirePrepared(items, server);
                    if (type != Type.TAKEAWAY) {
                        throw new IllegalArgumentException("Only takeaway orders can be collected.");
                    }
                }
                case CANCELLED -> {}
            }
        }
    }

    private static void requirePrepared(List<Item> items, ServerOrder server) {
        Status required = server == null ? Status.READY : server.status();
        if (items.stream().noneMatch(item -> item.status() == required)
                || items.stream().anyMatch(item -> item.status() != required
                        && (server == null || item.status() != Status.CANCELLED))) {
            throw new IllegalArgumentException("Every order item must be ready.");
        }
    }

    public enum Type { DINE_IN, TAKEAWAY }
    public enum Status {
        RECEIVED("New"), PREPARING("Preparing"), READY("Ready"),
        SERVED("Served"), COLLECTED("Collected"), CANCELLED("Cancelled");

        public static final List<Status> LANES = List.of(RECEIVED, PREPARING, READY);

        public final String label;

        Status(String label) {
            this.label = label;
        }

        public Status next() {
            return switch (this) {
                case RECEIVED -> PREPARING;
                case PREPARING -> READY;
                case READY, SERVED, COLLECTED, CANCELLED -> throw new IllegalStateException("Item cannot advance");
            };
        }
    }

    public enum Station {
        HOT("Hot Kitchen"), COLD("Cold Preparation"), BEVERAGES("Beverages");

        public final String label;

        Station(String label) {
            this.label = label;
        }
    }

    public record Item(long id, String name, int quantity, Station station, String options,
                       String instructions, String allergens, String crossContact, Status status, ServerItem server) {
        public Item(long id, String name, int quantity, Station station, String options,
                    String instructions, String allergens, String crossContact, Status status) {
            this(id, name, quantity, station, options, instructions, allergens, crossContact, status, null);
        }

        public boolean actionable() {
            return (status == Status.RECEIVED || status == Status.PREPARING)
                    && (server == null || server.cleared());
        }

        public Item {
            Objects.requireNonNull(status);
        }

        public Item advance() {
            return new Item(id, name, quantity, station, options, instructions, allergens, crossContact, status.next());
        }
    }

    public boolean rush() {
        return server != null ? server.rush() : !rushHistory.isEmpty() && rushHistory.getLast().action() == RushChange.Action.APPLY_RUSH;
    }

    public Ticket withRushChange(RushChange change) {
        List<RushChange> changes = new ArrayList<>(rushHistory);
        changes.add(change);
        return new Ticket(id, type, destination, orderedAt, delayTarget, changes, items, completion);
    }

    public String reference() {
        return server == null ? "#A" + id : server.id().toString();
    }

    public Status status() {
        return server == null ? statusOf(items) : server.status();
    }

    public static Status statusOf(List<Item> items) {
        Status first = items.getFirst().status();
        return items.stream().allMatch(item -> item.status() == first) ? first : Status.PREPARING;
    }

    public boolean delayedAt(OffsetDateTime now) {
        return server != null ? server.delayed() : status() != Status.READY && !now.isBefore(orderedAt.plus(delayTarget));
    }

    public Ticket withItems(List<Item> updated, OffsetDateTime changedAt) {
        OrderCompletion result = completion;
        if (result == null && statusOf(updated) == Status.READY) {
            result = new OrderCompletion(OrderCompletion.Status.READY, changedAt);
        }
        return new Ticket(id, type, destination, orderedAt, delayTarget, rushHistory, updated, result);
    }
}
