package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.state.ReviewState;
import com.roseroots.customer.state.ReviewState.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ReviewStateTest {
    private final Draft rating = new Draft(5, Set.of(Topic.FOOD_QUALITY), "Delicious");
    private final TerminalSession session = session(false);

    @Test void unavailableReviewsRetainSeparateSessionDraftsAndCannotSend() {
        var state = new ReviewState();
        var visit = state.visit(session);
        assertThrows(IllegalArgumentException.class, () -> new Draft(6, Set.of(), ""));
        assertFalse(visit.canSend());
        visit.edit(rating);
        visit.submit();
        assertEquals(rating, state.visit(session).draft());
        assertEquals(Phase.DRAFT, visit.phase());
        assertNull(visit.receipt());
        assertFalse(state.visit(session(false)).draft().hasFeedback());
    }

    @Test void acceptanceRequiresMatchingServerIdentityAndPreventsDuplicates() {
        var service = new FixtureSubmission();
        var state = new ReviewState(service, Runnable::run);
        var visit = state.visit(session);
        visit.edit(rating);
        assertFalse(visit.canSend());
        visit.refresh();
        visit.submit();
        visit.submit();
        assertEquals(1, service.attempts.size());
        assertEquals(Phase.SENDING, visit.phase());
        assertNull(visit.receipt());
        service.result.complete(new Receipt(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(Phase.UNCERTAIN, visit.phase());
        service.result = new CompletableFuture<>();
        visit.retry();
        assertSame(service.attempts.getFirst(), service.attempts.getLast());
        var accepted = new Receipt(UUID.fromString(session.sessionId()), UUID.randomUUID());
        service.result.complete(accepted);
        assertEquals(Phase.ACCEPTED, visit.phase());
        assertEquals(accepted, state.visit(session).receipt());
        visit.submit();
        visit.edit(new Draft(1, Set.of(), ""));
        assertEquals(2, service.attempts.size());
        assertEquals(rating, visit.draft());
        var demo = state.visit(session(true));
        demo.edit(rating);
        assertFalse(demo.canSend());
    }

    @Test void definiteRejectionKeepsOptionalFeedbackWithoutFalseConfirmation() {
        var service = new FixtureSubmission();
        var visit = new ReviewState(service, Runnable::run).visit(session);
        var commentOnly = new Draft(null, Set.of(), "Kind service");
        visit.edit(commentOnly);
        visit.refresh();
        assertTrue(visit.canSend());
        visit.submit();
        service.result.completeExceptionally(new Rejected());
        assertEquals(Phase.REJECTED, visit.phase());
        assertEquals(commentOnly, visit.draft());
        assertNull(visit.receipt());
    }

    @Test void serverEligibilityAndExistingReviewControlSubmission() {
        var service = new FixtureSubmission();
        service.eligibility = new Eligibility(false, null);
        var visit = new ReviewState(service, Runnable::run).visit(session);
        visit.edit(rating);
        visit.refresh();
        assertTrue(visit.checked());
        assertFalse(visit.canSend());
        service.eligibility = new Eligibility(true, null);
        visit.refresh();
        visit.edit(new Draft(4, Set.of(), "a".repeat(501)));
        assertFalse(visit.canSend());
        visit.edit(rating);
        assertTrue(visit.canSend());
        var persisted = new Receipt(UUID.fromString(session.sessionId()), UUID.randomUUID());
        service.eligibility = new Eligibility(false, persisted);
        visit.refresh();
        assertEquals(persisted, visit.receipt());
        assertFalse(visit.canSend());
    }

    private static TerminalSession session(boolean demo) {
        return new TerminalSession(UUID.randomUUID().toString(), new TerminalDevice("terminal", null, demo),
                ServiceMode.TAKEAWAY, TerminalSession.Status.OPEN, "P-001", List.of());
    }

    private static final class FixtureSubmission implements Submission {
        final List<Attempt> attempts = new ArrayList<>();
        CompletableFuture<Receipt> result = new CompletableFuture<>();
        Eligibility eligibility = new Eligibility(true, null);
        @Override public boolean available() { return true; }
        @Override public CompletableFuture<Eligibility> eligibility(UUID sessionId) {
            return CompletableFuture.completedFuture(eligibility);
        }
        @Override public CompletableFuture<Receipt> send(Attempt attempt) {
            attempts.add(attempt);
            return result;
        }
    }
}
