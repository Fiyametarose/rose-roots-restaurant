package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.RushChange;
import com.roseroots.kitchen.model.RushChange.Action;
import com.roseroots.kitchen.model.StaffMember;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Item;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Predicate;

public final class MockKitchenService implements KitchenService {
    private final TicketRepository repository;
    private final RushAuthorization authorization;
    private final Clock clock;

    public MockKitchenService(TicketRepository repository, RushAuthorization authorization, Clock clock) {
        this.repository = repository;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Override
    public List<Ticket> activeTickets() {
        return repository.tickets().stream().filter(MockKitchenService::isActive).toList();
    }

    @Override
    public List<Ticket> historyTickets() {
        return repository.tickets().stream().filter(ticket -> ticket.completion() != null).toList();
    }

    private static boolean isActive(Ticket ticket) {
        return ticket.completion() == null || ticket.completion().active();
    }

    @Override
    public void advance(long ticketId, Station station, Status expected) {
        updateItems(ticketId, expected, item -> station == null || item.station() == station);
    }

    @Override
    public void advanceItem(long ticketId, long itemId, Status expected) {
        updateItems(ticketId, expected, item -> item.id() == itemId);
    }

    @Override
    public void changeRush(long ticketId, boolean enabled, String reason) {
        StaffMember actor = authorization.requireAuthorizer();
        if (reason == null || reason.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("Enter a reason for this Rush change using words or a reference.");
        }
        Action action = enabled ? Action.APPLY_RUSH : Action.REMOVE_RUSH;
        repository.update(ticketId, ticket -> {
            if (!isActive(ticket) || ticket.status() == Status.READY) {
                throw new IllegalStateException("Priority changes apply to unfinished orders.");
            }
            if (ticket.rush() == enabled) {
                throw new IllegalStateException("This ticket already has that Rush setting.");
            }
            return ticket.withRushChange(new RushChange(ticket.id(), action, reason.strip(), actor, OffsetDateTime.now(clock)));
        });
    }

    private void updateItems(long ticketId, Status expected, Predicate<Item> scope) {
        if (expected == null || expected == Status.READY) {
            throw new IllegalArgumentException("Choose a preparation state that can advance.");
        }
        repository.update(ticketId, ticket -> {
            if (!isActive(ticket)) {
                throw new IllegalStateException("This order has already left active preparation.");
            }
            return ticket.withItems(ticket.items().stream()
                    .map(item -> scope.test(item) && item.status() == expected ? item.advance() : item)
                    .toList(), OffsetDateTime.now(clock));
        });
    }
}
