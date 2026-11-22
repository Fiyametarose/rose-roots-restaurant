package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.AllergyReview;
import com.roseroots.customer.models.CartPricing;
import com.roseroots.customer.models.ConfiguredDish;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.SessionOrder;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.models.WaitEstimate;
import com.roseroots.customer.services.mock.LocalDiningSessionService;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.state.CustomerState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class CartCompletionTest {
    @Test
    void clearRequiresConfirmationAndPreservesSessionAllergiesAndPlacedOrders() {
        var state = new CustomerState();
        var session = new LocalDiningSessionService(true).openOrResume(ServiceMode.DINE_IN).toCompletableFuture().join();
        var previousOrder = new SessionOrder("old", session.sessionId(), BigDecimal.TEN);
        var active = new TerminalSession(session.sessionId(), session.device(), session.mode(),
                session.status(), null, List.of(previousOrder));
        assertTrue(state.activateSession(active, false));
        var allergy = new AllergyDeclaration(false, Set.of(AllergyDeclaration.Allergen.MILK), "Sesame");
        state.rememberAllergy(allergy);
        assertTrue(state.addToDraft(item()));
        assertFalse(state.clearDraft(false));
        assertEquals(1, state.cart().count());
        assertTrue(state.clearDraft(true));
        assertEquals(0, state.cart().count());
        assertEquals(0, state.cart().total().signum());
        assertSame(active, state.terminalSession());
        assertEquals(List.of(previousOrder), state.terminalSession().orders());
        assertEquals(allergy, state.sessionAllergy());
    }

    @Test
    void groupsOnlyIdenticalChoicesNotesAndAllergyDeclarations() {
        var state = new CustomerState();
        state.activateSession(new LocalDiningSessionService(true)
                .openOrResume(ServiceMode.DINE_IN).toCompletableFuture().join(), false);
        var dish = item();
        var options = ConfiguredDish.defaults(dish);
        var allergy = new AllergyDeclaration(false, Set.of(AllergyDeclaration.Allergen.MILK), "");
        var first = new ConfiguredDish(dish, 1, options, "Less oil", allergy);
        assertTrue(state.saveDish(null, first));
        assertTrue(state.saveDish(null, first));
        assertEquals(1, state.cart().lines().size());
        assertEquals(2, state.cart().lines().getFirst().quantity());
        assertTrue(state.saveDish(null, new ConfiguredDish(dish, 1, options, "No oil", allergy)));
        assertTrue(state.saveDish(null, new ConfiguredDish(dish, 1, options, "Less oil", AllergyDeclaration.unreviewed())));
        assertEquals(3, state.cart().lines().size());
        assertEquals(AllergyReview.Status.PENDING_ALLERGY_REVIEW,
                AllergyReview.forDish(first, state.sessionAllergy()).status());
    }

    @Test
    void appliesOnlyConfiguredPercentageChargesAndUsesCurrencyPrecision() {
        var kes = Currency.getInstance("KES");
        var subtotal = new BigDecimal("12.55");
        var absent = CartPricing.calculate(subtotal, new LocalRestaurantSettings().cartCharges(), kes);
        assertEquals(subtotal, absent.total());
        assertTrue(absent.charges().isEmpty());
        var configured = CartPricing.calculate(subtotal, List.of(
                new CartPricing.Rule("cart.charge.service", BigDecimal.TEN),
                new CartPricing.Rule("cart.charge.tax", new BigDecimal("16"))), kes);
        assertEquals(new BigDecimal("1.26"), configured.charges().get(0).amount());
        assertEquals(new BigDecimal("2.01"), configured.charges().get(1).amount());
        assertEquals(new BigDecimal("15.82"), configured.total());
        assertEquals("KSh\u00A015.82", MenuText.price(configured.total(), kes, Locale.ENGLISH));
    }

    @Test
    void defaultEstimateIsUnavailableAndExpiredMockResultIsDisregarded() {
        var request = new WaitEstimate.Request("session-1", List.of());
        assertTrue(WaitEstimateService.unavailable().estimate(request).toCompletableFuture().join().isEmpty());
        var now = Instant.parse("2026-11-20T18:00:00Z");
        var estimate = new WaitEstimate(Duration.ofMinutes(10), Duration.ofMinutes(20), now, now.plusSeconds(30));
        WaitEstimateService mock = query -> CompletableFuture.completedFuture(Optional.of(estimate));
        var provided = mock.estimate(request).toCompletableFuture().join();
        assertEquals(Optional.of(estimate), WaitEstimateService.current(provided, now));
        assertTrue(WaitEstimateService.current(provided, now.plusSeconds(30)).isEmpty());
    }

    private static com.roseroots.customer.models.MenuItem item() {
        return new MockMenuService().loadCatalog().toCompletableFuture().join().item("doro-wat");
    }
}
