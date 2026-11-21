package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.Ticket;
import java.util.List;
import java.util.function.UnaryOperator;

public interface TicketRepository {
    List<Ticket> activeTickets();

    // Apply the update atomically against the current ticket.
    void update(long ticketId, UnaryOperator<Ticket> change);
}
