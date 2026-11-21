package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.ConfiguredDish;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.state.OrderConfirmation;
import com.roseroots.customer.services.mock.MockMenuService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderConfirmationTest {
    private final Instant now = Instant.parse("2026-11-20T18:00:00Z");
    private final UUID sessionId = UUID.randomUUID();
    private final CustomerState customer = new CustomerState();
    private final FakeOrders orders = new FakeOrders();
    private final OrderConfirmation flow = new OrderConfirmation(customer, orders,
            Clock.fixed(now, ZoneOffset.UTC), Runnable::run);
    private final AllergyDeclaration disclosed = new AllergyDeclaration(false, Set.of(AllergyDeclaration.Allergen.MILK), "Sesame");
    private final Runnable changed = () -> {};

    @BeforeEach void setup() {
        customer.activateSession(session(sessionId, false), false);
        customer.rememberAllergy(disclosed);
        var sample = new MockMenuService().loadCatalog().toCompletableFuture().join().item("doro-wat");
        var item = new MenuItem(UUID.randomUUID().toString(), sample.categoryId(), sample.cuisine(),
                sample.name(), sample.summary(), sample.description(), sample.price(), sample.image(), sample.ingredients(),
                sample.safety(), sample.spice(), sample.availability(), sample.highlights(), sample.preparationTime(), List.of());
        assertTrue(customer.saveDish(null, new ConfiguredDish(item, 1, Map.of(), "Less oil", disclosed)));
    }

    @Test void undeclaredAllergiesDoNotRequireAnAcknowledgmentOrChangeVisitState() {
        var original = customer.cart().lines().getFirst();
        customer.rememberAllergy(AllergyDeclaration.unreviewed());
        assertTrue(customer.saveDish(original.id(),
                new ConfiguredDish(original.item(), 1, Map.of(), "", AllergyDeclaration.unreviewed())));
        assertFalse(flow.requiresAllergyAcknowledgement());
        flow.requestQuote(changed);
        flow.submit(false, changed);
        assertEquals(1, orders.calls.size());
        assertTrue(orders.draft.visitAllergy().none());
        assertEquals(AllergyDeclaration.unreviewed(), customer.sessionAllergy());
    }

    @Test void selectedCustomizationConflictBlocksQuotesUntilEdited() {
        var original = customer.cart().lines().getFirst();
        var seed = original.item();
        var choice = new MenuItem.CustomizationGroup.Choice(UUID.randomUUID().toString(), seed.name(),
                java.math.BigDecimal.ZERO, Set.of(MenuItem.Allergen.MILK));
        var group = new MenuItem.CustomizationGroup(UUID.randomUUID().toString(), seed.name(),
                MenuItem.CustomizationGroup.Selection.EXTRA, 0, 1, List.of(choice));
        var item = new MenuItem(seed.id(), seed.categoryId(), seed.cuisine(), seed.name(), seed.summary(),
                seed.description(), seed.price(), seed.image(), seed.ingredients(), seed.safety(), seed.spice(),
                seed.availability(), seed.highlights(), seed.preparationTime(), List.of(group));
        assertTrue(customer.saveDish(original.id(), new ConfiguredDish(item, 1,
                Map.of(group.id(), Set.of(choice.id())), "", AllergyDeclaration.unreviewed())));
        assertTrue(flow.hasIngredientConflict());
        assertFalse(flow.canQuote());
        flow.requestQuote(changed);
        assertEquals(0, orders.quoteCalls);
        assertEquals(disclosed, customer.sessionAllergy());
        assertTrue(customer.saveDish(original.id(), new ConfiguredDish(item, 1, Map.of(), "", AllergyDeclaration.unreviewed())));
        assertFalse(flow.hasIngredientConflict());
        assertTrue(flow.requiresAllergyAcknowledgement());
        flow.requestQuote(changed);
        flow.submit(true, changed);
        orders.response.complete(accepted(sessionId, false));
        assertEquals(Ordering.Review.PENDING_ALLERGY_REVIEW, flow.accepted().lines().getFirst().review());
    }

    @Test void requiresSessionAndConnectivityAndDoesNotCallBackendForDemo() {
        var offline = new OrderConfirmation(customer, OrderService.unavailable(), Clock.systemUTC(), Runnable::run);
        assertFalse(offline.active());
        customer.activateSession(session(sessionId, true), false);
        flow.requestQuote(changed);
        flow.submit(true, changed);
        assertFalse(flow.active());
        assertNull(orders.draft);
        assertEquals(1, customer.cart().count());
    }

    @Test void quotedOrderRequiresReviewAndRemovesOnlyUnchangedSubmittedLines() {
        flow.requestQuote(changed);
        assertNotNull(flow.quote());
        flow.submit(false, changed);
        assertTrue(orders.calls.isEmpty());
        flow.submit(true, changed);
        flow.submit(true, changed);
        assertEquals(1, orders.calls.size());
        assertEquals(disclosed, orders.draft.visitAllergy());
        assertEquals(disclosed, orders.draft.lines().getFirst().allergy());
        assertEquals("Less oil", orders.draft.lines().getFirst().instructions());
        var original = customer.cart().lines().getFirst();
        assertTrue(customer.saveDish(null, new ConfiguredDish(original.item(), 2, Map.of(), "More oil", disclosed)));
        orders.response.complete(accepted(sessionId, false));
        assertEquals(OrderConfirmation.Phase.ACCEPTED, flow.phase());
        assertEquals(Ordering.Review.PENDING_ALLERGY_REVIEW, flow.accepted().lines().getFirst().review());
        assertEquals(2, customer.cart().count());
        assertNull(customer.cart().line(original.id()));
        assertEquals(disclosed, customer.sessionAllergy());
    }

    @Test void uncertainFailurePreservesCartAndRetriesExactPayloadAndKey() {
        flow.requestQuote(changed);
        flow.submit(true, changed);
        orders.response.completeExceptionally(new OrderService.Problem("CONNECTION_INTERRUPTED", true));
        assertEquals(OrderConfirmation.Phase.RETRY, flow.phase());
        assertEquals(1, customer.cart().count());
        flow.requestQuote(changed);
        assertEquals(1, orders.quoteCalls);
        orders.response = new CompletableFuture<>();
        flow.submit(false, changed);
        assertEquals(2, orders.calls.size());
        assertEquals(orders.calls.getFirst(), orders.calls.getLast());
        orders.response.complete(accepted(sessionId, false));
        assertEquals(0, customer.cart().count());
    }

    @Test void addingASecondDishPreservesVisitDisclosureWithoutCopyingItIntoTheItem() {
        var item = customer.cart().lines().getFirst().item();
        assertTrue(customer.addToDraft(item));
        var added = customer.cart().lines().getLast().dish();
        assertEquals(disclosed, customer.sessionAllergy());
        assertEquals(AllergyDeclaration.unreviewed(), added.allergy());
        assertEquals(com.roseroots.customer.models.AllergyReview.Status.PENDING_ALLERGY_REVIEW,
                com.roseroots.customer.models.AllergyReview.forDish(added, customer.sessionAllergy()).status());
    }

    @Test void idempotencyConflictIsNotRetryableForTheSameDraft() {
        flow.requestQuote(changed);
        flow.submit(true, changed);
        orders.response.completeExceptionally(new OrderService.Problem("IDEMPOTENCY_CONFLICT", false));
        assertEquals("IDEMPOTENCY_CONFLICT", flow.problem());
        assertFalse(flow.unresolved());
        assertFalse(flow.canQuote());
        assertEquals(1, customer.cart().count());
        assertEquals(disclosed, customer.sessionAllergy());
        flow.submit(true, changed);
        assertEquals(1, orders.calls.size());
        assertTrue(customer.changeQuantity(customer.cart().lines().getFirst().id(), 2));
        assertTrue(flow.canQuote());
    }

    @Test void invalidOptionExplainsWhichDraftLineWasRejected() {
        flow.requestQuote(changed);
        flow.submit(true, changed);
        orders.response.completeExceptionally(new OrderService.Problem("INVALID_SELECTION", false, List.of(
                new OrderService.Violation("/lines/0/selections/0/optionIds/0", "INVALID_OPTION", "Option changed"))));
        assertEquals("INVALID_SELECTION", flow.problem());
        assertTrue(flow.diagnostics().getFirst().contains("Doro Wat"));
        assertTrue(flow.diagnostics().getFirst().contains("optionIds/0"));
        assertEquals(1, customer.cart().count());
        assertEquals(disclosed, customer.cart().lines().getFirst().dish().allergy());
    }

    @Test void definitiveRejectionPreservesCartAndRequiresAnotherQuote() {
        flow.requestQuote(changed);
        flow.submit(true, changed);
        orders.response.completeExceptionally(new OrderService.Problem("PRICE_CHANGED", false));
        assertEquals("PRICE_CHANGED", flow.problem());
        assertNull(flow.quote());
        flow.submit(true, changed);
        assertEquals(1, orders.calls.size());
        assertEquals(1, customer.cart().count());
        flow.requestQuote(changed);
        assertEquals(2, orders.quoteCalls);
    }

    @Test void mismatchedResponseCannotClearCart() {
        flow.requestQuote(changed);
        flow.submit(true, changed);
        orders.response.complete(accepted(UUID.randomUUID(), false));
        assertNull(flow.accepted());
        assertTrue(flow.unresolved());
        assertEquals(1, customer.cart().count());
    }

    @Test void switchingContextsHidesOldReceiptAndPreservesNewCart() {
        flow.requestQuote(changed);
        flow.submit(true, changed);
        var item = customer.cart().lines().getFirst().item();
        customer.activateSession(session(UUID.randomUUID(), false), true);
        assertTrue(customer.addToDraft(item));
        orders.response.complete(accepted(sessionId, false));
        assertNull(flow.accepted());
        assertEquals(1, customer.cart().count());
    }

    @Test void cartEditInvalidatesQuoteAndDeclinedLineIsDisplayedAsNonBillable() {
        flow.requestQuote(changed);
        customer.changeQuantity(customer.cart().lines().getFirst().id(), 2);
        assertNull(flow.quote());
        flow.submit(true, changed);
        assertTrue(orders.calls.isEmpty());
        flow.requestQuote(changed);
        flow.submit(true, changed);
        orders.response.complete(accepted(sessionId, true));
        assertEquals(Ordering.Review.DECLINED, flow.accepted().lines().getFirst().review());
        assertEquals(0, flow.accepted().totals().totalMinor());
        assertEquals(0, customer.cart().count());
    }

    @Test void orderMoreCreatesASecondOrderRoundWithTheSameSessionAndANewKey() {
        var dish = customer.cart().lines().getFirst().dish();
        flow.requestQuote(changed);
        flow.submit(true, changed);
        var firstOrder = accepted(sessionId, false);
        orders.response.complete(firstOrder);
        assertEquals(0, customer.cart().count());
        assertEquals(firstOrder.id(), flow.accepted().id());

        assertTrue(customer.saveDish(null, dish));
        orders.response = new CompletableFuture<>();
        flow.requestQuote(changed);
        flow.submit(true, changed);
        var secondOrder = accepted(sessionId, false);
        orders.response.complete(secondOrder);
        assertEquals(2, orders.calls.size());
        assertEquals(orders.calls.getFirst().draft().sessionId(), orders.calls.getLast().draft().sessionId());
        assertNotEquals(orders.calls.getFirst().key(), orders.calls.getLast().key());
        assertNotEquals(firstOrder.id(), flow.accepted().id());
        assertEquals(0, customer.cart().count());
    }

    private TerminalSession session(UUID id, boolean demo) {
        return new TerminalSession(id.toString(), new TerminalDevice("terminal", null, demo), ServiceMode.TAKEAWAY,
                TerminalSession.Status.OPEN, "R07", List.of());
    }

    private Ordering.Accepted accepted(UUID id, boolean declined) {
        var original = orders.draft.lines().getFirst();
        var line = new Ordering.AcceptedLine(UUID.randomUUID(), orders.quote.lines().getFirst(),
                original.allergy(), original.instructions(), declined ? Ordering.Status.CANCELLED : Ordering.Status.RECEIVED,
                declined ? Ordering.Review.DECLINED : Ordering.Review.PENDING_ALLERGY_REVIEW, !declined, "");
        return new Ordering.Accepted(UUID.randomUUID(), id, ServiceMode.TAKEAWAY, line.preparation(), now,
                orders.draft.visitAllergy(), List.of(line), declined ? totals(0) : orders.quote.totals());
    }

    private static Ordering.Totals totals(long price) {
        return new Ordering.Totals(price, 0, 0, price, "KES");
    }

    private record Call(Ordering.Draft draft, UUID quote, UUID key) {}

    private final class FakeOrders implements OrderService {
        final List<Call> calls = new ArrayList<>();
        CompletableFuture<Ordering.Accepted> response = new CompletableFuture<>();
        Ordering.Draft draft;
        Ordering.Quote quote;
        int quoteCalls;

        @Override public CompletableFuture<Ordering.Quote> quote(Ordering.Draft input) {
            quoteCalls++;
            draft = input;
            var line = draft.lines().getFirst();
            var price = new Ordering.Price(line.clientLineId(), UUID.fromString(line.menuItemId()), "Doro Wat",
                    line.quantity(), 145000, List.of(), 145000L * line.quantity());
            quote = new Ordering.Quote(UUID.randomUUID(), draft.sessionId(), now.plusSeconds(60),
                    List.of(price), totals(price.extendedMinor()));
            return CompletableFuture.completedFuture(quote);
        }

        @Override public CompletableFuture<Ordering.Accepted> submit(Ordering.Draft draft, UUID quote, UUID key) {
            calls.add(new Call(draft, quote, key));
            return response;
        }
    }
}
