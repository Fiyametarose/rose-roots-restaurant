package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.api.SpringBootTakeawaySessionService;
import com.roseroots.customer.services.mock.LocalTakeawaySessionService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class TakeawaySessionServiceTest {
    @Test
    void demoResumesSameReferenceUntilCloseAndCannotSubmitRealOrders() {
        var service = new LocalTakeawaySessionService("demo");
        var first = service.openOrResume().toCompletableFuture().join();
        assertSame(first, service.openOrResume().toCompletableFuture().join());
        assertTrue(first.pickupReference().startsWith("DEMO-"));
        assertFalse(first.canSubmitOrders());
        assertEquals(TerminalSession.Status.CLOSED, service.closeForStaff().status());
        var next = service.openOrResume().toCompletableFuture().join();
        assertNotEquals(first.pickupReference(), next.pickupReference());
        assertNotEquals(first.sessionId(), next.sessionId());
    }

    @Test
    void productionUsesOnlyServerReturnedReferencesAndCanRetryAFailure() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var serverSession = new TerminalSession("authoritative-id", new TerminalDevice("terminal", null, false),
                ServiceMode.TAKEAWAY, TerminalSession.Status.OPEN, "P-123", List.of());
        var service = new SpringBootTakeawaySessionService(mode -> {
            assertEquals(ServiceMode.TAKEAWAY, mode);
            return attempts.getAndIncrement() == 0
                    ? CompletableFuture.failedFuture(new IllegalStateException("Server unavailable"))
                    : CompletableFuture.completedFuture(serverSession);
        });

        assertThrows(CompletionException.class, () -> service.openOrResume().toCompletableFuture().join());
        assertSame(serverSession, service.openOrResume().toCompletableFuture().join());
        assertTrue(serverSession.canSubmitOrders());
    }

    @Test
    void productionRejectsDemoResponses() {
        var demo = new LocalTakeawaySessionService("demo");
        var service = new SpringBootTakeawaySessionService(mode -> demo.openOrResume());
        assertThrows(CompletionException.class, () -> service.openOrResume().toCompletableFuture().join());
    }
}
