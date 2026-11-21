package com.roseroots.kitchen;

import com.roseroots.kitchen.model.KitchenBoard;
import com.roseroots.kitchen.model.RushChange.Action;
import com.roseroots.kitchen.model.StaffMember;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import com.roseroots.kitchen.service.DemoTickets;
import com.roseroots.kitchen.service.MockKitchenService;
import com.roseroots.kitchen.service.MockRushAuthorization;
import com.roseroots.kitchen.service.InMemoryTicketRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KitchenBoardTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-20T12:00:00-07:00");
    private final List<Ticket> tickets = DemoTickets.create(NOW);
    private final MockRushAuthorization authorization = new MockRushAuthorization();
    private final MockKitchenService service = new MockKitchenService(new InMemoryTicketRepository(tickets),
            authorization, Clock.fixed(NOW.toInstant(), NOW.getOffset()));

    @Test
    void queueSortsRushThenDelayedThenNormalAndOldestWithinEachBand() {
        Ticket rush = ticket(1023);
        Ticket normal = ticket(1026);
        Ticket newerRush = new Ticket(201, rush.type(), rush.destination(), NOW.minusMinutes(1),
                rush.delayTarget(), rush.rushHistory(), rush.items());
        Ticket olderNormal = new Ticket(202, normal.type(), normal.destination(), NOW.minusMinutes(3),
                normal.delayTarget(), List.of(), normal.items());
        var columns = KitchenBoard.columns(List.of(normal, newerRush, ticket(1024), olderNormal, rush),
                null, NOW);
        assertEquals(List.of(1023L, 201L, 1024L, 202L, 1026L), ids(columns.get(Status.RECEIVED)));
        assertTrue(rush.delayedAt(NOW));
        assertTrue(ticket(1024).delayedAt(NOW));
        assertFalse(ticket(1024).rush());
        assertFalse(normal.delayedAt(NOW));
        assertTrue(normal.delayedAt(NOW.plusMinutes(13)));
        assertFalse(ticket(1017).delayedAt(NOW.plusHours(1)));
    }

    @Test
    void appliesOnlyExpectedItemTransitionsAndDerivesWholeOrderStatus() {
        service.advanceItem(1023, 1, Status.PREPARING);
        assertEquals(Status.RECEIVED, current(1023).status());
        service.advanceItem(1023, 1, Status.RECEIVED);
        assertEquals(Status.PREPARING, current(1023).status());
        service.advanceItem(1023, 1, Status.PREPARING);
        assertEquals(Status.PREPARING, current(1023).status());
        service.advance(1023, Station.HOT, Status.RECEIVED);
        assertEquals(Status.RECEIVED, current(1023).items().getLast().status());
        service.advance(1023, Station.BEVERAGES, Status.RECEIVED);
        service.advance(1023, Station.BEVERAGES, Status.RECEIVED);
        assertEquals(Status.PREPARING, current(1023).items().getLast().status());
        service.advance(1023, null, Status.PREPARING);
        assertEquals(Status.READY, current(1023).status());
        assertThrows(IllegalArgumentException.class, () -> service.advance(1023, null, Status.READY));
    }

    @Test
    void derivesCountsFromCurrentStateAndFilter() {
        var all = KitchenBoard.columns(tickets, null, NOW);
        assertEquals(3, all.get(Status.RECEIVED).size());
        assertEquals(2, all.get(Status.PREPARING).size());
        assertEquals(2, all.get(Status.READY).size());

        var drinks = KitchenBoard.columns(tickets, Station.BEVERAGES, NOW);
        assertEquals(List.of(1023L), ids(drinks.get(Status.RECEIVED)));
        assertEquals(List.of(1020L), ids(drinks.get(Status.READY)));
        assertEquals(1, drinks.get(Status.RECEIVED).getFirst().items().size());

        service.advance(1024, null, Status.RECEIVED);
        var updated = KitchenBoard.columns(service.activeTickets(), null, NOW);
        assertEquals(2, updated.get(Status.RECEIVED).size());
        assertEquals(3, updated.get(Status.PREPARING).size());
    }

    @Test
    void handoffRequiresEveryItemReadyEvenWhenStationColumnShowsReady() {
        service.advance(1023, Station.BEVERAGES, Status.RECEIVED);
        service.advanceItem(1023, 2, Status.PREPARING);
        service.advanceItem(1023, 2, Status.PREPARING);
        assertEquals(Status.PREPARING, current(1023).status());
        assertEquals(List.of(1023L, 1020L), ids(KitchenBoard.columns(
                service.activeTickets(), Station.BEVERAGES, NOW).get(Status.READY)));
        assertEquals(List.of(1017L), handoffIds(Type.DINE_IN));
        assertEquals(List.of(1018L), handoffIds(Type.TAKEAWAY));

        service.advance(1023, Station.HOT, Status.RECEIVED);
        service.advance(1023, Station.HOT, Status.PREPARING);
        assertEquals(Status.READY, current(1023).status());
        assertEquals(List.of(1023L, 1017L), handoffIds(Type.DINE_IN));

        service.advance(1020, Station.HOT, Status.PREPARING);
        assertEquals(List.of(1018L, 1020L), handoffIds(Type.TAKEAWAY));
        var counts = KitchenBoard.summary(service.activeTickets());
        assertEquals(2L, counts.get(Status.RECEIVED));
        assertEquals(1L, counts.get(Status.PREPARING));
        assertEquals(4L, counts.get(Status.READY));
    }

    @Test
    void individualActionsAndBulkActionsAffectOnlyEligibleItemsInScope() {
        service.advanceItem(1023, 1, Status.RECEIVED);
        assertEquals(Status.RECEIVED, current(1023).items().getLast().status());
        service.advance(1023, Station.BEVERAGES, Status.PREPARING);
        assertEquals(Status.PREPARING, current(1023).items().getFirst().status());
        assertEquals(Status.RECEIVED, current(1023).items().getLast().status());

        service.advance(1023, null, Status.PREPARING);
        assertEquals(Status.READY, current(1023).items().getFirst().status());
        assertEquals(Status.RECEIVED, current(1023).items().getLast().status());
        assertEquals(Status.PREPARING, current(1023).status());

        service.advance(1023, null, Status.RECEIVED);
        service.advance(1023, null, Status.RECEIVED);
        assertEquals(Status.PREPARING, current(1023).items().getLast().status());
        assertEquals(Status.READY, current(1023).items().getFirst().status());
    }

    @Test
    void delayUsesTicketTargetAndSubmissionEvenWhilePreparing() {
        service.advance(1026, null, Status.RECEIVED);
        Ticket order = current(1026);
        assertFalse(order.delayedAt(NOW.plusMinutes(13).minusNanos(1)));
        assertTrue(order.delayedAt(NOW.plusMinutes(13)));
        assertFalse(order.rush());

        service.advance(1026, null, Status.PREPARING);
        assertFalse(current(1026).delayedAt(NOW.plusMinutes(30)));
    }

    @Test
    void onlyAuthorizedStaffCanChangeRushAndAReasonIsRequired() {
        assertThrows(SecurityException.class, () -> service.changeRush(1024, true, "Replacement"));
        assertThrows(SecurityException.class, () -> service.changeRush(1023, false, "Resolved"));
        authorization.selectProfile(MockRushAuthorization.SUPERVISOR.id());
        assertThrows(IllegalArgumentException.class, () -> service.changeRush(1024, true, "   "));
        assertThrows(IllegalArgumentException.class, () -> service.changeRush(1023, false, ""));
        assertThrows(IllegalArgumentException.class, () -> service.changeRush(1023, false, "…!"));
        assertThrows(IllegalArgumentException.class, () -> service.changeRush(1023, false, null));
        assertThrows(IllegalStateException.class, () -> service.changeRush(1017, true, "Replacement"));
        assertTrue(current(1024).rushHistory().isEmpty());
        assertTrue(current(1023).rush());
    }

    @Test
    void rushChangePreservesPreparationAndRecordsWhoWhenAndWhyIncludingRemoval() {
        authorization.selectProfile(MockRushAuthorization.SUPERVISOR.id());
        Ticket before = current(1020);
        var preparingBefore = queueIds(Status.PREPARING);
        service.changeRush(before.id(), true, " Incorrect dish replacement ");

        Ticket promoted = current(before.id());
        var applied = promoted.rushHistory().getLast();
        assertEquals(Status.PREPARING, promoted.status());
        assertEquals(before.items(), promoted.items());
        assertEquals(preparingBefore, queueIds(Status.PREPARING));
        assertEquals("Incorrect dish replacement", applied.reason());
        assertEquals(MockRushAuthorization.SUPERVISOR, applied.actor());
        assertEquals(NOW, applied.changedAt());
        assertEquals(before.id(), applied.orderId());
        assertEquals(Action.APPLY_RUSH, applied.action());
        assertEquals(MockRushAuthorization.SUPERVISOR.id(), applied.staffId());
        assertTrue(promoted.rush());
        assertThrows(IllegalStateException.class,
                () -> service.changeRush(before.id(), true, "Duplicate tap"));

        service.changeRush(before.id(), false, "Replacement underway");
        Ticket removed = current(before.id());
        assertEquals(2, removed.rushHistory().size());
        assertEquals(applied, removed.rushHistory().getFirst());
        var removal = removed.rushHistory().getLast();
        assertEquals("Replacement underway", removal.reason());
        assertEquals(before.id(), removal.orderId());
        assertEquals(Action.REMOVE_RUSH, removal.action());
        assertEquals(MockRushAuthorization.SUPERVISOR.id(), removal.staffId());
        assertEquals(NOW, removal.changedAt());
        assertFalse(removed.rush());
        assertEquals(before.items(), removed.items());
    }

    @Test
    void waitingQueueReordersAfterRushAndRestoresIndependentDelayAfterRemoval() {
        authorization.selectProfile(MockRushAuthorization.SUPERVISOR.id());
        service.changeRush(1026, true, "Missed ticket");
        assertEquals(List.of(1023L, 1026L, 1024L), queueIds(Status.RECEIVED));

        service.changeRush(1024, true, "Service recovery");
        assertTrue(current(1024).rush());
        assertTrue(current(1024).delayedAt(NOW));
        assertEquals(List.of(1023L, 1024L, 1026L), queueIds(Status.RECEIVED));

        service.changeRush(1024, false, "Recovery addressed");
        assertTrue(current(1024).delayedAt(NOW));
        assertFalse(current(1024).rush());
        assertEquals(List.of(1023L, 1026L, 1024L), queueIds(Status.RECEIVED));
    }

    @Test
    void currentProfileReplacesPreviousPermissionsAndManagerCanAuthorizeBothActions() {
        assertEquals(3, MockRushAuthorization.STAFF.stream().map(StaffMember::id).distinct().count());
        assertEquals(List.of(StaffMember.Role.KITCHEN_STAFF, StaffMember.Role.SUPERVISOR, StaffMember.Role.MANAGER),
                MockRushAuthorization.STAFF.stream().map(StaffMember::role).toList());

        authorization.selectProfile(MockRushAuthorization.MANAGER.id());
        service.changeRush(1024, true, "Coordinate mains");
        assertEquals(MockRushAuthorization.MANAGER.id(), current(1024).rushHistory().getFirst().staffId());

        authorization.selectProfile(MockRushAuthorization.KITCHEN_STAFF.id());
        assertThrows(SecurityException.class, () -> service.changeRush(1024, false, "Mains coordinated"));
        assertThrows(SecurityException.class, () -> service.changeRush(1026, true, "Replacement"));
        assertTrue(current(1024).rush());
        service.advanceItem(1024, 3, Status.RECEIVED);
        assertEquals(Status.PREPARING, current(1024).status());

        authorization.selectProfile(MockRushAuthorization.MANAGER.id());
        service.changeRush(1024, false, "Mains coordinated");
        assertEquals(Action.REMOVE_RUSH, current(1024).rushHistory().getLast().action());
        assertEquals(MockRushAuthorization.MANAGER.id(), current(1024).rushHistory().getLast().staffId());
        assertTrue(current(1024).delayedAt(NOW));

        authorization.selectProfile("unknown");
        assertThrows(SecurityException.class, () -> service.changeRush(1024, true, "Replacement"));
        assertEquals(2, current(1024).rushHistory().size());
    }

    @Test
    void waitingQueueUsesCurrentTimeWhenAnOrderCrossesItsOwnDelayTarget() {
        Ticket normal = ticket(1026);
        Ticket shorterTarget = new Ticket(1030, normal.type(), normal.destination(), NOW.minusMinutes(1),
                java.time.Duration.ofMinutes(2), List.of(), normal.items());
        List<Ticket> waiting = List.of(normal, shorterTarget);
        assertEquals(List.of(1026L, 1030L), KitchenBoard.priorityQueue(waiting, Status.RECEIVED, NOW)
                .stream().map(Ticket::id).toList());
        assertEquals(List.of(1030L, 1026L), KitchenBoard.priorityQueue(waiting, Status.RECEIVED, NOW.plusMinutes(1))
                .stream().map(Ticket::id).toList());
        assertFalse(shorterTarget.rush());
    }

    private List<Long> queueIds(Status status) {
        return KitchenBoard.priorityQueue(service.activeTickets(), status, NOW).stream().map(Ticket::id).toList();
    }

    private List<Long> handoffIds(Type type) {
        return KitchenBoard.handoff(service.activeTickets()).get(type).stream().map(Ticket::id).toList();
    }

    private Ticket ticket(long id) {
        return tickets.stream().filter(ticket -> ticket.id() == id).findFirst().orElseThrow();
    }

    private Ticket current(long id) {
        return service.activeTickets().stream().filter(ticket -> ticket.id() == id).findFirst().orElseThrow();
    }

    private static List<Long> ids(List<KitchenBoard.Card> cards) {
        return cards.stream().map(card -> card.ticket().id()).toList();
    }
}
