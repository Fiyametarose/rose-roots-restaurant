package com.roseroots.kitchen.model;

import com.roseroots.kitchen.model.Ticket.Item;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class KitchenBoard {
    private static final Comparator<Ticket> OLDEST = Comparator
            .comparing((Ticket ticket) -> ticket.orderedAt().toInstant()).thenComparingLong(Ticket::id);

    private KitchenBoard() {}

    public record Card(Ticket ticket, List<Item> items, Station station) {}

    public static Map<Status, List<Card>> columns(List<Ticket> tickets, Station station,
                                                  OffsetDateTime now) {
        var columns = new EnumMap<Status, List<Card>>(Status.class);
        for (Status status : Status.LANES) {
            columns.put(status, new ArrayList<>());
        }
        for (Ticket ticket : tickets) {
            List<Item> items = ticket.items().stream()
                    .filter(item -> station == null || item.station() == station)
                    .toList();
            if (!items.isEmpty()) {
                Status status = station == null ? ticket.status() : Ticket.statusOf(items);
                if (!Status.LANES.contains(status)) {
                    continue;
                }
                columns.get(status).add(new Card(ticket, items, station));
            }
        }
        Comparator<Card> oldest = Comparator.comparing(Card::ticket, OLDEST);
        columns.get(Status.RECEIVED).sort(Comparator.comparing(Card::ticket, queueOrder(now)));
        columns.get(Status.PREPARING).sort(oldest);
        columns.get(Status.READY).sort(oldest);
        columns.replaceAll((status, cards) -> List.copyOf(cards));
        return Map.copyOf(columns);
    }

    public record StationQueue(List<Card> cards, Map<Status, Long> counts) {}

    public static StationQueue stationQueue(List<Ticket> tickets, Station station, OffsetDateTime now) {
        var columns = columns(tickets, java.util.Objects.requireNonNull(station), now);
        var cards = new ArrayList<Card>();
        var counts = new EnumMap<Status, Long>(Status.class);
        for (Status status : Status.LANES) {
            cards.addAll(columns.get(status));
            counts.put(status, 0L);
        }
        for (Card card : cards) {
            for (Item item : card.items()) {
                if (counts.containsKey(item.status())) {
                    counts.merge(item.status(), 1L, Long::sum);
                }
            }
        }
        return new StationQueue(List.copyOf(cards), Map.copyOf(counts));
    }

    public static Map<Status, Long> summary(List<Ticket> tickets) {
        var counts = new EnumMap<Status, Long>(Status.class);
        for (Status status : Status.LANES) {
            counts.put(status, 0L);
        }
        for (Ticket ticket : tickets) {
            counts.merge(ticket.status(), 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }

    public static Map<Type, List<Ticket>> handoff(List<Ticket> tickets) {
        var groups = new EnumMap<Type, List<Ticket>>(Type.class);
        for (Type type : Type.values()) {
            groups.put(type, tickets.stream()
                    .filter(ticket -> ticket.type() == type && ticket.status() == Status.READY
                            && (ticket.completion() == null || ticket.completion().active()))
                    .sorted(OLDEST)
                    .toList());
        }
        return Map.copyOf(groups);
    }

    public static List<Ticket> priorityQueue(List<Ticket> tickets, Status status, OffsetDateTime now) {
        return tickets.stream().filter(ticket -> ticket.status() == status)
                .sorted(status == Status.RECEIVED ? queueOrder(now) : OLDEST).toList();
    }

    private static Comparator<Ticket> queueOrder(OffsetDateTime now) {
        return Comparator.comparingInt((Ticket ticket) -> priority(ticket, now)).thenComparing(OLDEST);
    }

    private static int priority(Ticket ticket, OffsetDateTime now) {
        if (ticket.rush()) {
            return 0;
        }
        return ticket.delayedAt(now) ? 1 : 2;
    }
}
