package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import java.util.List;

public interface KitchenService {
    default boolean local() { return true; }

    default boolean writesAvailable() { return true; }

    List<Ticket> activeTickets();

    List<Ticket> historyTickets();

    // Advance only items currently in the expected state and visible in the station scope.
    void advance(long ticketId, Station station, Status expected);

    void advanceItem(long ticketId, long itemId, Status expected);

    default void advanceItem(long ticketId, Ticket.Item displayedItem) {
        advanceItem(ticketId, displayedItem.id(), displayedItem.status());
    }

    void changeRush(long ticketId, boolean enabled, String reason);
}
