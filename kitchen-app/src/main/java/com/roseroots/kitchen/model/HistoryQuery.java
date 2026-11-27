package com.roseroots.kitchen.model;

import com.roseroots.kitchen.model.OrderCompletion.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HistoryQuery {
    private HistoryQuery() {}

    public static List<Ticket> filter(List<Ticket> tickets, String search, Status status, Type type) {
        String term = search.strip().toLowerCase(Locale.ROOT);
        return tickets.stream()
                .filter(ticket -> ticket.completion() != null)
                .filter(ticket -> status == null || ticket.completion().status() == status)
                .filter(ticket -> type == null || ticket.type() == type)
                .filter(ticket -> matches(ticket.reference(), term) || matches(ticket.displayReference(), term)
                        || matches(ticket.destination(), term)
                        || ticket.items().stream().anyMatch(item -> matches(item.name(), term)))
                .sorted(Comparator.comparing(HistoryQuery::completionTime).reversed().thenComparingLong(Ticket::id))
                .toList();
    }

    private static Instant completionTime(Ticket ticket) {
        var at = ticket.completion().at();
        return (at == null ? ticket.orderedAt() : at).toInstant();
    }

    private static boolean matches(String value, String term) {
        return value.toLowerCase(Locale.ROOT).contains(term);
    }
}
