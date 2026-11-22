package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.Payment;
import com.roseroots.customer.state.PaymentCheckout;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class PaymentCheckoutTest {
    private final Backend backend = new Backend();
    private final Memory journal = new Memory();
    private final PaymentCheckout checkout = new PaymentCheckout(backend, journal, backend.session);

    @Test void lostResponseRecoversByReadingPendingAttemptThenFetchesVerifiedReceipt() {
        backend.loseStart = true;
        assertProblem("CONNECTION_INTERRUPTED", () -> get(checkout.start(backend.balanceValue())));
        assertNotNull(journal.load(backend.session).key());
        var reopened = new PaymentCheckout(backend, journal, backend.session);
        var pending = get(reopened.refresh());
        assertEquals(Payment.Status.PENDING, pending.attempt().status());
        assertNull(pending.receipt());
        assertEquals(1, backend.starts);
        assertEquals(0, backend.receiptReads);
        backend.status = Payment.Status.SUCCEEDED;
        var settled = get(reopened.refresh());
        assertEquals(0, settled.balance().outstanding());
        assertEquals(Payment.Status.SUCCEEDED, settled.attempt().status());
        assertEquals(backend.receiptId, settled.receipt().id());
        assertEquals(1, backend.receiptReads);
    }

    @Test void uncertainAlreadySettledAttemptReplaysOnlyItsOriginalKey() {
        backend.loseStart = true;
        assertProblem("CONNECTION_INTERRUPTED", () -> get(checkout.start(backend.balanceValue())));
        backend.status = Payment.Status.SUCCEEDED;
        assertProblem("PAYMENT_OUTCOME_UNKNOWN", () -> get(checkout.refresh()));
        var recovered = get(checkout.recover(true));
        assertEquals(Payment.Status.SUCCEEDED, recovered.attempt().status());
        assertEquals(backend.receiptId, recovered.receipt().id());
        assertEquals(2, backend.starts);
        assertEquals(backend.key, journal.load(backend.session).key());
    }

    @Test void cancellationStaysPendingUntilStaffResolvesAndRetryUsesNewKey() {
        var first = get(checkout.start(backend.balanceValue()));
        var original = backend.key;
        var requested = get(checkout.cancel(first.attempt()));
        assertTrue(requested.attempt().cancellationRequested());
        assertEquals(Payment.Status.PENDING, requested.attempt().status());
        assertNull(requested.receipt());
        backend.status = Payment.Status.CANCELLED;
        assertEquals(Payment.Status.CANCELLED, get(checkout.refresh()).attempt().status());
        var retry = get(checkout.start(backend.balanceValue()));
        assertEquals(Payment.Status.PENDING, retry.attempt().status());
        assertNotEquals(original, backend.key);
    }

    @Test void blocksStaleReviewAndRejectsCrossSessionData() {
        var old = backend.balanceValue();
        backend.version++;
        assertProblem("BILL_VERSION_CONFLICT", () -> get(checkout.start(old)));
        assertEquals(0, backend.starts);
        assertThrows(OrderService.Problem.class, () -> checkout.start(new Payment.Balance(
                UUID.randomUUID(), 2, 700, 0, 700, Set.of(), null, true)));
        assertNull(journal.load(UUID.randomUUID()));
    }

    @Test void rejectsMismatchedReceiptAndKeepsAuthoritativeSuccess() {
        get(checkout.start(backend.balanceValue()));
        backend.status = Payment.Status.SUCCEEDED;
        backend.wrongReceiptSession = true;
        var result = get(checkout.refresh());
        assertEquals(Payment.Status.SUCCEEDED, result.attempt().status());
        assertTrue(result.receiptUnavailable());
        assertNull(result.receipt());
    }

    @Test void discoversClosedVisitReceiptWithoutJournalAndWithoutStartingAnotherAttempt() {
        get(checkout.start(backend.balanceValue()));
        journal.save(backend.session, null);
        backend.status = Payment.Status.SUCCEEDED;
        backend.discoverSettlement = true;
        var view = get(checkout.refresh());
        assertEquals(backend.receiptId, view.receipt().id());
        assertEquals(1, backend.starts);
        assertFalse(view.balance().canStart());
    }

    @Test void failedStatusAllowsReviewAndCashMustBeAvailableAndUnblocked() {
        assertFalse(new Payment.Balance(backend.session, 1, 900, 0, 900, Set.of(), null, false).canStart());
        assertFalse(new Payment.Balance(backend.session, 1, 900, 0, 900, Set.of("CHEF_DECISION_PENDING"), null, true).canStart());
        get(checkout.start(backend.balanceValue()));
        backend.status = Payment.Status.FAILED;
        assertEquals(Payment.Status.FAILED, get(checkout.refresh()).attempt().status());
        assertTrue(get(checkout.refresh()).balance().canStart());
    }

    private static <T> T get(java.util.concurrent.CompletionStage<T> result) { return result.toCompletableFuture().join(); }

    private static void assertProblem(String code, Runnable call) {
        var failure = assertThrows(CompletionException.class, call::run);
        assertEquals(code, assertInstanceOf(OrderService.Problem.class, failure.getCause()).code());
    }

    private static final class Memory implements PaymentCheckout.Journal {
        private final HashMap<UUID, PaymentCheckout.Recovery> values = new HashMap<>();
        @Override public PaymentCheckout.Recovery load(UUID session) { return values.get(session); }
        @Override public void save(UUID session, PaymentCheckout.Recovery entry) {
            if (entry == null) values.remove(session); else values.put(session, entry);
        }
    }

    private static final class Backend implements PaymentService {
        private final UUID session = UUID.randomUUID();
        private final UUID receiptId = UUID.randomUUID();
        private UUID id;
        private UUID key;
        private Payment.Status status;
        private long version = 1;
        private int starts;
        private int receiptReads;
        private boolean loseStart;
        private boolean requested;
        private boolean wrongReceiptSession;
        private boolean discoverSettlement;

        private Payment.Balance balanceValue() {
            boolean paid = status == Payment.Status.SUCCEEDED;
            boolean pending = status == Payment.Status.PENDING;
            return new Payment.Balance(session, version, 900, paid ? 900 : 0, paid ? 0 : 900,
                    paid && discoverSettlement ? Set.of("ALREADY_SETTLED", "SESSION_NOT_OPEN")
                            : paid ? Set.of("ALREADY_SETTLED") : pending ? Set.of("PAYMENT_PENDING") : Set.of(),
                    pending ? id : null, paid && discoverSettlement ? id : null, true);
        }
        private Payment.Attempt attemptValue() {
            return new Payment.Attempt(id, session, status, version, requested ? 2 : 1, 900, requested,
                    status == Payment.Status.SUCCEEDED ? receiptId : null);
        }

        @Override public CompletableFuture<Payment.Balance> balance(UUID session) {
            assertEquals(this.session, session);
            return CompletableFuture.completedFuture(balanceValue());
        }
        @Override public CompletableFuture<Payment.Attempt> start(UUID session, long version, UUID key) {
            assertEquals(this.session, session);
            assertEquals(this.version, version);
            starts++;
            if (!key.equals(this.key)) {
                assertTrue(status == null || status == Payment.Status.CANCELLED || status == Payment.Status.FAILED);
                this.key = key;
                id = UUID.randomUUID();
                status = Payment.Status.PENDING;
                requested = false;
            }
            if (loseStart) {
                loseStart = false;
                return CompletableFuture.failedFuture(new OrderService.Problem("CONNECTION_INTERRUPTED", true));
            }
            return CompletableFuture.completedFuture(attemptValue());
        }
        @Override public CompletableFuture<Payment.Attempt> attempt(UUID session, UUID id) {
            assertEquals(this.session, session);
            assertEquals(this.id, id);
            return CompletableFuture.completedFuture(attemptValue());
        }
        @Override public CompletableFuture<Payment.Attempt> cancel(UUID session, UUID id, long expectedVersion) {
            assertEquals(1, expectedVersion);
            requested = true;
            return attempt(session, id);
        }
        @Override public CompletableFuture<Payment.Receipt> receipt(UUID session, UUID id) {
            assertEquals(this.session, session);
            assertEquals(receiptId, id);
            receiptReads++;
            return CompletableFuture.completedFuture(new Payment.Receipt(id,
                    wrongReceiptSession ? UUID.randomUUID() : session, this.id, 900, Instant.now(), List.of()));
        }
    }
}
