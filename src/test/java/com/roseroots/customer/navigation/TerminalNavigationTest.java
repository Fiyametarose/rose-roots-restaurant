package com.roseroots.customer.navigation;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.SessionOrder;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.mock.LocalDiningSessionService;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.state.CustomerState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

class TerminalNavigationTest {
    private final LocalDiningSessionService demo = new LocalDiningSessionService(true);
    private final CustomerState state = new CustomerState();

    @Test
    void welcomeRoundTripKeepsLanguageSessionCartAndAcceptedOrders() {
        var dineIn = resolve(demo, ServiceMode.DINE_IN);
        var order = new SessionOrder("accepted-1", dineIn.sessionId(), new BigDecimal("1450"));
        var withOrder = new TerminalSession(dineIn.sessionId(), dineIn.device(), dineIn.mode(),
                dineIn.status(), null, List.of(order));
        assertTrue(state.activateSession(withOrder, false));
        state.setLocale(Locale.forLanguageTag("am"));
        assertTrue(state.addToDraft(dish()));

        var host = new StackPane();
        var navigator = new Navigator(host);
        var menu = new StackPane();
        var welcome = new StackPane();
        navigator.register(Route.MENU, () -> menu);
        navigator.register(Route.WELCOME, () -> welcome);
        navigator.show(Route.MENU);
        navigator.show(Route.WELCOME);
        assertSame(welcome, host.getChildren().getFirst());
        assertSame(withOrder, state.terminalSession());
        var resumed = resolve(demo, ServiceMode.DINE_IN);
        assertEquals(dineIn.sessionId(), resumed.sessionId());
        assertTrue(state.activateSession(withOrder, false));
        navigator.show(Route.MENU);
        assertSame(menu, host.getChildren().getFirst());
        assertEquals("am", state.locale().getLanguage());
        assertEquals(1, state.cart().count());
        assertEquals(List.of(order), state.terminalSession().orders());
    }

    @Test
    void switchingModesRequiresConsentAndKeepsTheTwoSessionsSeparate() {
        var dineIn = resolve(demo, ServiceMode.DINE_IN);
        var takeaway = resolve(demo, ServiceMode.TAKEAWAY);
        assertEquals("07", dineIn.device().table().displayNumber());
        assertTrue(dineIn.device().demo());
        assertNull(takeaway.device().table());
        assertTrue(takeaway.pickupReference().startsWith("DEMO-"));
        assertNotEquals(dineIn.sessionId(), takeaway.sessionId());
        assertTrue(state.activateSession(dineIn, false));
        assertTrue(state.addToDraft(dish()));
        assertTrue(state.wouldDiscardCart(takeaway));
        assertFalse(state.activateSession(takeaway, false));
        assertSame(dineIn, state.terminalSession());
        assertEquals(1, state.cart().count());

        assertTrue(state.activateSession(takeaway, true));
        assertEquals(0, state.cart().count());
        assertSame(takeaway, state.terminalSession());
        assertSame(dineIn, resolve(demo, ServiceMode.DINE_IN));
    }

    @Test
    void staffCloseCreatesANewVisitAndOrderAmountsAggregateBySession() {
        var first = resolve(demo, ServiceMode.DINE_IN);
        var firstOrder = new SessionOrder("one", first.sessionId(), new BigDecimal("1000"));
        var secondOrder = new SessionOrder("two", first.sessionId(), new BigDecimal("450"));
        var visit = new TerminalSession(first.sessionId(), first.device(), first.mode(),
                first.status(), null, List.of(firstOrder, secondOrder));
        assertEquals(new BigDecimal("1450"), visit.runningBill());
        assertEquals(TerminalSession.Status.CLOSED, demo.closeForStaff(ServiceMode.DINE_IN).status());
        var next = resolve(demo, ServiceMode.DINE_IN);
        assertNotEquals(first.sessionId(), next.sessionId());
        assertEquals(first.device(), next.device());
        assertThrows(IllegalArgumentException.class, () -> new TerminalSession(next.sessionId(),
                next.device(), next.mode(), next.status(), null, List.of(firstOrder)));
    }

    @Test
    void anUnassignedTerminalCannotAddToTheDraftOrSubmitOrders() {
        var unassigned = resolve(new LocalDiningSessionService(false), ServiceMode.DINE_IN);
        assertEquals(TerminalSession.Status.UNASSIGNED, unassigned.status());
        assertFalse(unassigned.canSubmitOrders());
        assertTrue(state.activateSession(unassigned, false));
        assertFalse(state.addToDraft(dish()));
        assertEquals(0, state.cart().count());
    }

    private static TerminalSession resolve(LocalDiningSessionService service, ServiceMode mode) {
        return service.openOrResume(mode).toCompletableFuture().join();
    }

    private static com.roseroots.customer.models.MenuItem dish() {
        return new MockMenuService().loadCatalog().toCompletableFuture().join().item("doro-wat");
    }
}
