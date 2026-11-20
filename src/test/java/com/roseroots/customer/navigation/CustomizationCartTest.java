package com.roseroots.customer.navigation;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.AllergyDeclaration.Allergen;
import com.roseroots.customer.models.ConfiguredDish;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.services.mock.LocalDiningSessionService;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.state.CustomerState;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomizationCartTest {
    private final MockMenuService menu = new MockMenuService();
    private final LocalDiningSessionService terminal = new LocalDiningSessionService(true);
    private final CustomerState state = new CustomerState();

    @Test
    void pricesAndEditsIndependentConfigurations() {
        open(ServiceMode.DINE_IN);
        var item = menu.loadCatalog().toCompletableFuture().join().item("doro-wat");
        var options = new HashMap<>(ConfiguredDish.defaults(item));
        options.put("portion", Set.of("large"));
        options.put("extras", Set.of("yogurt"));
        var dish = new ConfiguredDish(item, 2, options, "Less oil", AllergyDeclaration.unreviewed());
        assertEquals(new BigDecimal("3740"), dish.total());
        assertTrue(state.saveDish(null, dish));
        assertTrue(state.addToDraft(item));
        assertEquals(3, state.cart().count());
        var line = state.cart().lines().getFirst();
        assertTrue(state.changeQuantity(line.id(), 3));
        assertEquals("Less oil", state.cart().line(line.id()).dish().instructions());
        assertTrue(state.saveDish(line.id(), dish.withQuantity(1)));
        assertEquals(new BigDecimal("3320"), state.cart().total());
        state.removeDish(line.id());
        assertEquals(1, state.cart().count());
    }

    @Test
    void detectsIngredientCrossContactAndUnverifiedDeclarationsWithoutBlockingDraft() {
        open(ServiceMode.DINE_IN);
        var item = menu.loadCatalog().toCompletableFuture().join().item("doro-wat");
        var choices = new HashMap<>(ConfiguredDish.defaults(item));
        choices.put("side", Set.of("rice"));
        choices.put("extras", Set.of("yogurt"));
        var allergy = new AllergyDeclaration(false, Set.of(Allergen.MILK, Allergen.EGGS, Allergen.WHEAT), "");
        var dish = new ConfiguredDish(item, 1, choices, "Remove the egg", allergy);
        assertEquals(Set.of(com.roseroots.customer.models.MenuItem.Allergen.MILK,
                com.roseroots.customer.models.MenuItem.Allergen.EGGS), dish.review(allergy).ingredients());
        assertEquals(Set.of(com.roseroots.customer.models.MenuItem.Allergen.WHEAT), dish.review(allergy).crossContact());
        assertTrue(state.saveDish(null, dish));
        assertTrue(dish.review(new AllergyDeclaration(false, Set.of(), "Sesame")).uncertain());
        assertEquals(1, state.cart().count());
        assertEquals(allergy, state.cart().lines().getFirst().dish().allergy());
    }

    @Test
    void restoresVisitDeclarationAndDoesNotReuseItForTakeaway() {
        open(ServiceMode.DINE_IN);
        var visit = new AllergyDeclaration(false, Set.of(Allergen.EGGS), "");
        state.rememberAllergy(visit);
        open(ServiceMode.DINE_IN);
        assertEquals(visit, state.sessionAllergy());
        assertTrue(state.addToDraft(menu.loadCatalog().toCompletableFuture().join().item("doro-wat")));
        open(ServiceMode.TAKEAWAY);
        assertEquals(AllergyDeclaration.unreviewed(), state.sessionAllergy());
        open(ServiceMode.DINE_IN);
        assertEquals(visit, state.sessionAllergy());
        assertThrows(IllegalArgumentException.class, () -> new AllergyDeclaration(true, Set.of(Allergen.MILK), ""));
    }

    private void open(ServiceMode mode) {
        assertTrue(state.activateSession(terminal.openOrResume(mode).toCompletableFuture().join(), true));
    }
}
