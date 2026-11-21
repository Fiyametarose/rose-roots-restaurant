package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.Ticket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

public final class InMemoryTicketRepository implements TicketRepository {
    private final Map<Long, Ticket> tickets = new LinkedHashMap<>();

    public InMemoryTicketRepository(List<Ticket> initialTickets) {
        initialTickets.forEach(ticket -> tickets.put(ticket.id(), ticket));
    }

    @Override
    public synchronized List<Ticket> tickets() {
        return List.copyOf(tickets.values());
    }

    @Override
    public synchronized void update(long ticketId, UnaryOperator<Ticket> change) {
        Ticket ticket = tickets.get(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("Unknown ticket " + ticketId);
        }
        tickets.put(ticketId, change.apply(ticket));
    }
}
