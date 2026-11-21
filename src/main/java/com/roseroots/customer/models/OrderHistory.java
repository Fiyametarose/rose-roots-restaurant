package com.roseroots.customer.models;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OrderHistory(UUID sessionId, List<Ordering.Accepted> orders, Map<UUID, Ordering.Totals> orderTotals,
                           Ordering.Totals totals, Map<UUID, WaitEstimate> remaining) {
    public OrderHistory {
        Objects.requireNonNull(sessionId);
        orders = orders.stream().sorted(Comparator.comparing(Ordering.Accepted::acceptedAt).reversed()
                .thenComparing(Ordering.Accepted::id)).toList();
        orderTotals = Map.copyOf(orderTotals);
        remaining = Map.copyOf(remaining);
        var ids = new HashSet<UUID>();
        if (!validTotals(totals)) throw new IllegalArgumentException("Invalid session bill totals");
        for (var order : orders) {
            if (!sessionId.equals(order.sessionId()) || !ids.add(order.id())) {
                throw new IllegalArgumentException("Order history contains a foreign or repeated order");
            }
            var billed = orderTotals.get(order.id());
            if (!validTotals(order.totals())
                    || (billed == null ? order.totals().totalMinor() != 0 : !order.totals().equals(billed))
                    || order.totals().subtotalMinor() != billedLines(order).stream()
                    .mapToLong(line -> line.pricing().extendedMinor()).sum()) {
                throw new IllegalArgumentException("History and bill disagree. Reload the session.");
            }
        }
        if (totals.subtotalMinor() != orders.stream().mapToLong(order -> order.totals().subtotalMinor()).sum()) {
            throw new IllegalArgumentException("Session subtotal disagrees with the itemized orders");
        }
        if (!ids.containsAll(orderTotals.keySet()) || !ids.containsAll(remaining.keySet())) {
            throw new IllegalArgumentException("Data belongs to an unknown order");
        }
    }

    private static boolean validTotals(Ordering.Totals value) {
        return value != null && "KES".equals(value.currency()) && value.subtotalMinor() >= 0
                && value.serviceMinor() >= 0 && value.taxMinor() >= 0
                && value.totalMinor() == value.subtotalMinor() + value.serviceMinor() + value.taxMinor();
    }

    public static boolean current(Ordering.Accepted order) {
        return switch (order.status()) {
            case RECEIVED, PREPARING, READY -> true;
            case SERVED, COLLECTED, CANCELLED -> false;
        };
    }

    public static List<Ordering.AcceptedLine> billedLines(Ordering.Accepted order) {
        return order.lines().stream()
                .filter(line -> line.billable() && line.review() != Ordering.Review.DECLINED)
                .toList();
    }

    public Optional<WaitEstimate> remaining(UUID order, Instant now) {
        return Optional.ofNullable(remaining.get(order)).filter(value -> value.isCurrent(now));
    }
}
