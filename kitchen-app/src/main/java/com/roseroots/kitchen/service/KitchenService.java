package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import java.util.List;

public interface KitchenService {
    List<Ticket> activeTickets();

    // Advance only items currently in the expected state and visible in the station scope.
    void advance(long ticketId, Station station, Status expected);

    void advanceItem(long ticketId, long itemId, Status expected);

    void changeRush(long ticketId, boolean enabled, String reason);
}
