package com.roseroots.customer.services.api;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.roseroots.customer.models.OrderHistory;
import com.roseroots.customer.models.Ordering;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderHistoryTest {
    private final Instant now = Instant.parse("2026-11-20T18:00:00Z");

    @Test void groupsThreeIndependentOrdersWithoutMixingTheirPreparationAndAllergyStates() {
        var history = OrderTrackingFixture.history(now);
        var current = history.orders().stream().filter(OrderHistory::current).toList();
        assertEquals(2, current.size());
        assertEquals(OrderTrackingFixture.ACTIVE_ORDER, current.getFirst().id());
        assertEquals(Ordering.Status.RECEIVED, current.getFirst().lines().getFirst().preparation());
        assertEquals(Ordering.Review.PENDING_ALLERGY_REVIEW, current.getFirst().lines().getFirst().review());
        assertEquals(Ordering.Status.SERVED, history.orders().getLast().status());
        assertTrue(history.orders().stream().allMatch(order -> history.sessionId().equals(order.sessionId())));
    }

    @Test void authoritativeCombinedTotalExcludesDeclinedItems() {
        var history = OrderTrackingFixture.history(now);
        assertEquals(445000, history.totals().totalMinor());
        assertEquals(3, history.orders().stream().flatMap(order -> OrderHistory.billedLines(order).stream()).count());
        assertEquals(2, history.orders().getFirst().lines().size());
        assertEquals(145000, history.orderTotals().get(OrderTrackingFixture.ACTIVE_ORDER).totalMinor());
    }

    @Test void serverReviewUpdatesKeepTheSameLineAndOnlyTheServerChangesThePayableBill() {
        var first = OrderTrackingFixture.history(now).orders().getFirst();
        var pending = first.lines().getFirst();
        assertEquals(Ordering.Review.PENDING_ALLERGY_REVIEW, pending.review());
        var approved = updateLine(pending, Ordering.Status.RECEIVED, Ordering.Review.APPROVED_FOR_PREPARATION, true);
        var afterApproval = reviewSnapshot(first, approved, first.totals());
        assertEquals(pending.id(), afterApproval.orders().getFirst().lines().getFirst().id());
        assertEquals(Ordering.Review.APPROVED_FOR_PREPARATION, afterApproval.orders().getFirst().lines().getFirst().review());
        assertEquals(first.totals(), afterApproval.totals());

        var declined = updateLine(pending, Ordering.Status.CANCELLED, Ordering.Review.DECLINED, false);
        var afterDecline = reviewSnapshot(first, declined, OrderTrackingFixture.totals(0));
        assertEquals(0, afterDecline.totals().totalMinor());
        assertEquals(Ordering.Review.DECLINED, afterDecline.orders().getFirst().lines().getFirst().review());
        assertTrue(OrderHistory.billedLines(afterDecline.orders().getFirst()).isEmpty());
    }

    private static Ordering.AcceptedLine updateLine(Ordering.AcceptedLine line, Ordering.Status state,
                                                     Ordering.Review review, boolean billable) {
        return new Ordering.AcceptedLine(line.id(), line.pricing(), line.allergy(), line.instructions(),
                state, review, billable, "");
    }

    private static OrderHistory reviewSnapshot(Ordering.Accepted original, Ordering.AcceptedLine line,
                                               Ordering.Totals totals) {
        var updated = new Ordering.Accepted(original.id(), original.sessionId(), original.mode(), line.preparation(),
                original.acceptedAt(), original.visitAllergy(), List.of(line), totals);
        return new OrderHistory(updated.sessionId(), List.of(updated), Map.of(updated.id(), totals), totals, Map.of());
    }

    @Test void rejectsMismatchedSessionAndIncoherentOrderAndBillSnapshots() {
        var safe = OrderTrackingFixture.history(now);
        assertThrows(IllegalArgumentException.class, () -> new OrderHistory(UUID.randomUUID(), safe.orders(),
                safe.orderTotals(), safe.totals(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new OrderHistory(safe.sessionId(), safe.orders(),
                Map.of(safe.orders().getFirst().id(), OrderTrackingFixture.totals(200000)), safe.totals(), Map.of()));
    }

    @Test void displaysOnlyCurrentServerEstimates() {
        var history = OrderTrackingFixture.history(now);
        assertEquals(12, history.remaining(OrderTrackingFixture.ACTIVE_ORDER, now).orElseThrow().latest().toMinutes());
        assertTrue(history.remaining(OrderTrackingFixture.ACTIVE_ORDER, now.plusSeconds(90)).isEmpty());
        assertTrue(history.remaining(history.orders().getLast().id(), now).isEmpty());
    }

    @Test void parsesSharedHistoryAndBillWithoutAssumingAnEstimateField() throws Exception {
        var paths = JsonParser.parseString(Files.readString(Path.of("docs", "ordering-api-v1.openapi.json")))
                .getAsJsonObject().getAsJsonObject("paths");
        var orders = paths.getAsJsonObject("/sessions/{id}/orders").getAsJsonObject("get")
                .getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content")
                .getAsJsonObject("application/json").getAsJsonObject("example").getAsJsonArray("orders");
        var bill = paths.getAsJsonObject("/sessions/{id}/bill").getAsJsonObject("get")
                .getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content")
                .getAsJsonObject("application/json").getAsJsonObject("example");
        var accepted = SpringBootOrderService.readAccepted(orders.get(0).getAsJsonObject());
        var decoded = SpringBootOrderService.readHistory(accepted.sessionId(), List.of(accepted), bill);
        assertEquals(1, decoded.orders().size());
        assertEquals(accepted.totals(), decoded.totals());
        assertTrue(decoded.remaining().isEmpty());
    }
}
