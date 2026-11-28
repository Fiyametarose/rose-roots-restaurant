package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.AllergyReview;
import com.roseroots.customer.models.ConfiguredDish;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.state.CustomerState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDraftSessionTest {
    @TempDir Path folder;

    @Test
    void generatesStableDistinctInstallationIdsAndReadsOperatorAssignment() throws Exception {
        Path firstPath = folder.resolve("first.properties");
        var first = LocalDeviceConfiguration.load(firstPath);
        assertEquals(first, LocalDeviceConfiguration.load(firstPath));
        assertNotEquals(first.deviceId(), LocalDeviceConfiguration.load(folder.resolve("second.properties")).deviceId());
        assertNull(first.table());

        Files.writeString(firstPath, "device.id=" + first.deviceId() + "\ntable.id=table-07\ntable.displayNumber=07\n");
        var configured = LocalDeviceConfiguration.load(firstPath);
        assertEquals(first.deviceId(), configured.deviceId());
        assertEquals("table-07", configured.table().tableId());
        assertEquals("07", configured.table().displayNumber());
        assertFalse(configured.demo());
    }

    @Test
    void incompleteMappingIsRejected() throws Exception {
        Path path = folder.resolve("incomplete.properties");
        Files.writeString(path, "device.id=tablet-1\ntable.displayNumber=07\n");
        assertThrows(IllegalArgumentException.class, () -> LocalDeviceConfiguration.load(path));
    }

    @Test
    void normalDineInAndTakeawaySupportDraftEditsAndIsolateContexts() throws Exception {
        var service = new LocalDraftSessionService(LocalDeviceConfiguration.load(folder.resolve("tablet.properties")));
        var dineIn = service.openOrResume(ServiceMode.DINE_IN).toCompletableFuture().join();
        var takeaway = service.openOrResume(ServiceMode.TAKEAWAY).toCompletableFuture().join();
        assertEquals(TerminalSession.Status.LOCAL_DRAFT, dineIn.status());
        assertSame(dineIn, service.openOrResume(ServiceMode.DINE_IN).toCompletableFuture().join());
        assertNotEquals(dineIn.cartContextId(), takeaway.cartContextId());
        for (var context : List.of(dineIn, takeaway)) {
            assertTrue(context.allowsCart());
            assertFalse(context.canSubmitOrders());
            assertFalse(context.isOpen());
            assertNull(context.sessionId());
            assertNull(context.pickupReference());
        }
        var state = new CustomerState();
        var dish = new MockMenuService(false).loadCatalog().toCompletableFuture().join().item("doro-wat");
        var declaration = new AllergyDeclaration(false, Set.of(AllergyDeclaration.Allergen.MILK), "Sesame");
        assertTrue(state.activateSession(dineIn, false));
        state.rememberAllergy(declaration);
        assertTrue(state.saveDish(null, new ConfiguredDish(dish, 1, ConfiguredDish.defaults(dish), "Less oil", declaration)));
        var line = state.cart().lines().getFirst();
        assertTrue(state.changeQuantity(line.id(), 2));
        assertEquals(declaration, state.cart().lines().getFirst().dish().allergy());
        assertEquals(AllergyReview.Status.PENDING_ALLERGY_REVIEW,
                AllergyReview.forDish(line.dish(), state.sessionAllergy()).status());
        assertTrue(state.activateSession(dineIn, false));
        assertEquals(2, state.cart().count());
        assertFalse(state.activateSession(takeaway, false));
        assertEquals(2, state.cart().count());
        assertTrue(state.activateSession(takeaway, true));
        assertEquals(0, state.cart().count());
        assertEquals(AllergyDeclaration.unreviewed(), state.sessionAllergy());
        assertTrue(state.addToDraft(dish));
        state.removeDish(state.cart().lines().getFirst().id());
        assertEquals(0, state.cart().count());
        assertTrue(state.addToDraft(dish));
        assertTrue(state.clearDraft(true));
        assertEquals(0, state.cart().count());
        assertTrue(state.activateSession(dineIn, false));
        assertEquals(declaration, state.sessionAllergy());
    }
}
