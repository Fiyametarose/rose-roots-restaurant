package com.roseroots.customer.state;

import com.roseroots.customer.models.Payment;
import com.roseroots.customer.services.OrderService.Problem;
import com.roseroots.customer.services.PaymentService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class PaymentCheckout {
    public record Recovery(long billVersion, UUID key, UUID attemptId) {}
    public record View(Payment.Balance balance, Payment.Attempt attempt, Payment.Receipt receipt,
                       boolean receiptUnavailable) {}

    public interface Journal {
        Recovery load(UUID session);
        void save(UUID session, Recovery recovery);
    }

    private static final Set<String> DEFINITE_REJECTION = Set.of("BILL_VERSION_CONFLICT", "CHEF_DECISION_PENDING",
            "SESSION_NOT_OPEN", "PAYMENT_METHOD_UNAVAILABLE", "NOTHING_DUE");
    private final PaymentService service;
    private final Journal journal;
    private final UUID session;

    public PaymentCheckout(PaymentService service, Journal journal, UUID session) {
        this.service = service;
        this.journal = journal;
        this.session = session;
    }

    public CompletionStage<View> refresh() {
        return recover(false);
    }

    public UUID sessionId() { return session; }

    public CompletionStage<View> recover(boolean replayIfUnknown) {
        return service.balance(session).thenCompose(balance -> {
            requireSession(balance.sessionId());
            var entry = journal.load(session);
            if (balance.settledAttempt() != null) {
                return settledAttempt(service, session, balance).thenCompose(attempt -> describe(balance, attempt, entry,
                        entry != null && attempt.id().equals(entry.attemptId()) ? entry.key() : null));
            }
            if (entry != null && entry.attemptId() != null
                    && (balance.openAttempt() == null || balance.openAttempt().equals(entry.attemptId()))) {
                return service.attempt(session, entry.attemptId()).thenCompose(attempt ->
                        describe(balance, attempt, entry, entry.key()));
            }
            if (balance.openAttempt() != null) {
                return service.attempt(session, balance.openAttempt()).thenCompose(attempt ->
                        describe(balance, attempt, entry, null));
            }
            if (entry != null) {
                if (!replayIfUnknown) return CompletableFuture.failedFuture(new Problem("PAYMENT_OUTCOME_UNKNOWN", true));
                return send(balance, entry);
            }
            return CompletableFuture.completedFuture(new View(balance, null, null, false));
        });
    }

    public CompletionStage<View> start(Payment.Balance reviewed) {
        requireSession(reviewed.sessionId());
        if (!reviewed.canStart()) return CompletableFuture.failedFuture(new Problem("PAYMENT_NOT_AVAILABLE", false));
        var previous = journal.load(session);
        if (previous != null && previous.attemptId() == null) return recover(true);
        return service.balance(session).thenCompose(current -> {
            requireSession(current.sessionId());
            if (current.openAttempt() != null || current.settledAttempt() != null) return refresh();
            if (current.billVersion() != reviewed.billVersion() || current.outstanding() != reviewed.outstanding()) {
                return CompletableFuture.failedFuture(new Problem("BILL_VERSION_CONFLICT", false));
            }
            if (!current.canStart()) return CompletableFuture.failedFuture(new Problem("PAYMENT_NOT_AVAILABLE", false));
            if (previous != null) {
                return service.attempt(session, previous.attemptId()).thenCompose(attempt -> {
                    if (attempt.status() == Payment.Status.PENDING || attempt.status() == Payment.Status.SUCCEEDED) {
                        return describe(current, attempt, previous, previous.key());
                    }
                    return begin(current, previous);
                });
            }
            return begin(current, previous);
        });
    }

    public CompletionStage<View> cancel(Payment.Attempt attempt) {
        requireSession(attempt.sessionId());
        return service.cancel(session, attempt.id(), attempt.version()).thenCompose(ignored -> refresh());
    }

    private CompletionStage<View> begin(Payment.Balance balance, Recovery previous) {
        Recovery entry;
        synchronized (journal) {
            if (!java.util.Objects.equals(previous, journal.load(session))) return refresh();
            entry = new Recovery(balance.billVersion(), UUID.randomUUID(), null);
            journal.save(session, entry);
        }
        return send(balance, entry);
    }

    private CompletionStage<View> send(Payment.Balance balance, Recovery entry) {
        return CompletableFuture.completedFuture(entry)
                .thenCompose(value -> service.start(session, value.billVersion(), value.key()))
                .whenComplete((attempt, failure) -> {
                    var cause = root(failure);
                    if (cause instanceof Problem problem && DEFINITE_REJECTION.contains(problem.code())) {
                        synchronized (journal) {
                            if (entry.equals(journal.load(session))) journal.save(session, null);
                        }
                    }
                }).thenCompose(attempt -> describe(balance, attempt, entry, entry.key()));
    }

    private CompletionStage<View> describe(Payment.Balance balance, Payment.Attempt attempt, Recovery expected, UUID key) {
        requireSession(attempt.sessionId());
        synchronized (journal) {
            var updated = new Recovery(attempt.billVersion(), key, attempt.id());
            if (!updated.equals(expected) && java.util.Objects.equals(expected, journal.load(session))) {
                journal.save(session, updated);
            }
        }
        if (attempt.status() != Payment.Status.SUCCEEDED) {
            return CompletableFuture.completedFuture(new View(balance, attempt, null, false));
        }
        return verifiedReceipt(service, session, attempt)
                .handle((receipt, failure) -> new View(balance, attempt, receipt, failure != null));
    }

    public static CompletionStage<Payment.Receipt> discoverReceipt(PaymentService service, UUID session) {
        return service.balance(session).thenCompose(balance -> {
            if (!session.equals(balance.sessionId())) throw new Problem("INVALID_RESPONSE", false);
            if (balance.settledAttempt() == null) return CompletableFuture.completedFuture(null);
            return settledAttempt(service, session, balance).thenCompose(attempt -> verifiedReceipt(service, session, attempt));
        });
    }

    private static CompletionStage<Payment.Attempt> settledAttempt(PaymentService service, UUID session, Payment.Balance balance) {
        return service.attempt(session, balance.settledAttempt()).thenApply(attempt -> {
            if (!balance.settledAttempt().equals(attempt.id()) || !session.equals(attempt.sessionId())
                    || attempt.status() != Payment.Status.SUCCEEDED || attempt.amount() != balance.settled()) {
                throw new Problem("INVALID_RESPONSE", false);
            }
            return attempt;
        });
    }

    private static CompletionStage<Payment.Receipt> verifiedReceipt(PaymentService service, UUID session, Payment.Attempt attempt) {
        return CompletableFuture.completedFuture(attempt.receiptId()).thenCompose(id -> service.receipt(session, id))
                .thenApply(receipt -> {
                    if (receipt == null || !receipt.id().equals(attempt.receiptId()) || !session.equals(receipt.sessionId())
                            || !attempt.id().equals(receipt.paymentId()) || receipt.amount() != attempt.amount()) {
                        throw new Problem("INVALID_RESPONSE", false);
                    }
                    return receipt;
                });
    }

    public static Throwable root(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private void requireSession(UUID actual) {
        if (!session.equals(actual)) throw new Problem("INVALID_RESPONSE", false);
    }

    public static Journal persistent(String backend) {
        var namespace = UUID.nameUUIDFromBytes(backend.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var preferences = Preferences.userNodeForPackage(PaymentCheckout.class).node("payment-" + namespace);
        return new Journal() {
            @Override public Recovery load(UUID session) {
                String encoded = preferences.get(session.toString(), "");
                if (encoded.isEmpty()) return null;
                var parts = encoded.split(":", -1);
                return new Recovery(Long.parseLong(parts[0]), parse(parts[1]), parse(parts[2]));
            }

            @Override public void save(UUID session, Recovery recovery) {
                String key = session.toString();
                if (recovery == null) preferences.remove(key);
                else preferences.put(key, recovery.billVersion() + ":"
                        + encode(recovery.key()) + ":" + encode(recovery.attemptId()));
                try {
                    preferences.flush();
                } catch (BackingStoreException failure) {
                    throw new IllegalStateException("Payment recovery could not be stored", failure);
                }
            }

            private String encode(UUID id) { return id == null ? "" : id.toString(); }
            private UUID parse(String value) { return value.isEmpty() ? null : UUID.fromString(value); }
        };
    }
}
