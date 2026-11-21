package com.roseroots.kitchen;

import com.roseroots.kitchen.model.CustomerReview;
import com.roseroots.kitchen.model.CustomerReview.Topic;
import com.roseroots.kitchen.model.HistoryQuery;
import com.roseroots.kitchen.model.KitchenBoard;
import com.roseroots.kitchen.model.OrderCompletion;
import com.roseroots.kitchen.model.ReviewQuery;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import com.roseroots.kitchen.service.DemoTickets;
import com.roseroots.kitchen.service.InMemoryTicketRepository;
import com.roseroots.kitchen.service.KitchenReviewService;
import com.roseroots.kitchen.service.KitchenSync;
import com.roseroots.kitchen.service.LocalKitchenReviewService;
import com.roseroots.kitchen.service.LocalKitchenSync;
import com.roseroots.kitchen.service.MockKitchenService;
import com.roseroots.kitchen.service.MockRushAuthorization;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewsAndHistoryTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-20T12:00:00-07:00");
    private final MockKitchenService service = new MockKitchenService(
            new InMemoryTicketRepository(DemoTickets.createWithHistory(NOW)),
            new MockRushAuthorization(), Clock.fixed(NOW.toInstant(), NOW.getOffset()));

    @Test
    void kitchenFeedExcludesNonFoodReviewsAndCountsOnlyDisplayedMatches() {
        var reviews = LocalKitchenReviewService.sample(NOW).kitchenReviews();
        assertEquals(List.of(71L, 72L, 73L, 74L, 75L), reviews.stream().map(CustomerReview::id).toList());
        var all = ReviewQuery.filter(reviews, null, null);
        assertEquals(5, all.count());
        assertEquals(4.2, all.average().orElseThrow());
        var temperature = ReviewQuery.filter(reviews, null, Topic.TEMPERATURE);
        assertEquals(2, temperature.count());
        assertEquals(4.0, temperature.average().orElseThrow());
        var filtered = ReviewQuery.filter(reviews, 5, Topic.TEMPERATURE);
        assertEquals(1, filtered.count());
        assertEquals(5.0, filtered.average().orElseThrow());
        var noMatches = ReviewQuery.filter(reviews, 1, Topic.TASTE);
        assertEquals(0, noMatches.count());
        assertTrue(noMatches.average().isEmpty());
    }

    @Test
    void kitchenReviewContractAndSnapshotsAreReadOnly() {
        assertEquals(Set.of("kitchenReviews"), Arrays.stream(KitchenReviewService.class.getDeclaredMethods())
                .map(Method::getName).collect(java.util.stream.Collectors.toSet()));
        KitchenReviewService reviews = LocalKitchenReviewService.sample(NOW);
        var snapshot = reviews.kitchenReviews();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getFirst().topics().clear());
        assertEquals(5, reviews.kitchenReviews().size());
    }

    @Test
    void historyDistinguishesPreparationFromExplicitHandoffOutcomes() {
        var history = service.historyTickets();
        assertEquals(List.of(1017L, 1018L, 1014L, 1013L, 1012L, 1011L), ids(history));
        assertEquals(List.of(1017L, 1018L), ids(HistoryQuery.filter(history, "", OrderCompletion.Status.READY, null)));
        assertEquals(List.of(1014L, 1012L), ids(HistoryQuery.filter(history, "", OrderCompletion.Status.SERVED, null)));
        assertEquals(List.of(1013L), ids(HistoryQuery.filter(history, "", OrderCompletion.Status.COLLECTED, null)));
        assertEquals(List.of(1011L), ids(HistoryQuery.filter(history, "", OrderCompletion.Status.CANCELLED, null)));
        assertFalse(ids(service.activeTickets()).contains(1014L));
        assertEquals(List.of(1018L), ids(KitchenBoard.handoff(history).get(Type.TAKEAWAY)));
        assertThrows(IllegalStateException.class, () -> service.advance(1011, null, Status.RECEIVED));
    }

    @Test
    void searchCombinesOrderReferenceDestinationItemStatusAndType() {
        var history = service.historyTickets();
        assertEquals(List.of(1014L), ids(HistoryQuery.filter(history, "  a1014 ", null, null)));
        assertEquals(List.of(1012L), ids(HistoryQuery.filter(history, "TABLE 11", null, Type.DINE_IN)));
        assertEquals(List.of(1013L), ids(HistoryQuery.filter(history, "grilled FISH", null, Type.TAKEAWAY)));
        assertEquals(List.of(1013L), ids(HistoryQuery.filter(history, "p-013", OrderCompletion.Status.COLLECTED, Type.TAKEAWAY)));
        assertTrue(HistoryQuery.filter(history, "p-013", OrderCompletion.Status.SERVED, Type.TAKEAWAY).isEmpty());
    }

    @Test
    void historyReadsPreserveActiveOrderStateAndAddReadyOnlyAfterLastItem() {
        service.advance(1023, Station.HOT, Status.RECEIVED);
        service.advance(1023, Station.HOT, Status.PREPARING);
        var active = service.activeTickets();
        assertFalse(ids(service.historyTickets()).contains(1023L));
        HistoryQuery.filter(service.historyTickets(), "Table", null, Type.DINE_IN);
        assertEquals(active, service.activeTickets());

        service.advance(1023, Station.BEVERAGES, Status.RECEIVED);
        service.advance(1023, Station.BEVERAGES, Status.PREPARING);
        Ticket ready = service.historyTickets().stream().filter(ticket -> ticket.id() == 1023).findFirst().orElseThrow();
        assertEquals(new OrderCompletion(OrderCompletion.Status.READY, NOW), ready.completion());
        assertTrue(ids(service.activeTickets()).contains(1023L));
        assertTrue(ids(KitchenBoard.handoff(service.activeTickets()).get(Type.DINE_IN)).contains(1023L));
        assertFalse(ids(HistoryQuery.filter(service.historyTickets(), "", OrderCompletion.Status.SERVED, null)).contains(1023L));
    }

    @Test
    void localConnectionReportsNoServerAndDoesNotEmitRemoteEvents() {
        KitchenSync sync = new LocalKitchenSync();
        assertEquals(KitchenSync.Status.LOCAL, sync.status());
        assertEquals("Local mode — Server not connected", sync.status().label);
        sync.onOrdersChanged(() -> fail("Local mode cannot emit remote synchronization events")).run();
        assertEquals(KitchenSync.Status.LOCAL, sync.status());
    }

    private static List<Long> ids(List<Ticket> tickets) {
        return tickets.stream().map(Ticket::id).toList();
    }
}
