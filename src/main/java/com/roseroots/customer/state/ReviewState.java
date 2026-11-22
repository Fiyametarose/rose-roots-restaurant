package com.roseroots.customer.state;

import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.TerminalSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Customer-side drafts and retry state. Wire mapping belongs to the approved Reviews adapter. */
public final class ReviewState {
    public enum Topic { FOOD_QUALITY, TASTE, TEMPERATURE, ORDER_ACCURACY, PREPARATION_SPEED,
        CLEANLINESS, STAFF_HELPFULNESS, AMBIENCE }
    public enum Phase { DRAFT, SENDING, UNCERTAIN, REJECTED, ACCEPTED }

    public record Draft(Integer stars, Set<Topic> topics, String comment) {
        public Draft {
            if (stars != null && (stars < 1 || stars > 5)) throw new IllegalArgumentException("Expected 1–5 stars");
            topics = Set.copyOf(topics);
            comment = Objects.requireNonNull(comment);
        }

        public boolean hasFeedback() { return stars != null || !topics.isEmpty() || !comment.isBlank(); }
    }

    public record Attempt(UUID sessionId, UUID key, Draft draft) {}
    public record Receipt(UUID sessionId, UUID reviewId) {}
    public record Eligibility(boolean eligible, Receipt receipt) {}

    public interface Submission {
        boolean available();
        CompletionStage<Eligibility> eligibility(UUID sessionId);
        CompletionStage<Receipt> send(Attempt attempt);
    }

    /** Use only when the backend confirms that this attempt was rejected. */
    public static final class Rejected extends RuntimeException {}
    public static final class AlreadyExists extends RuntimeException {}
    public static final class NotEligible extends RuntimeException {}

    private final Map<String, Visit> visits = new HashMap<>();
    private final Submission service;
    private final Executor ui;

    public ReviewState() { this(null, Runnable::run); }

    public ReviewState(Submission service, Executor ui) {
        this.service = service;
        this.ui = ui;
    }

    public Visit visit(TerminalSession session) {
        if (session == null || session.cartContextId() == null) return new Visit(null);
        UUID authoritative = authoritativeId(session);
        return visits.computeIfAbsent(session.device().deviceId() + "/" + session.cartContextId(),
                context -> new Visit(authoritative));
    }

    private static UUID authoritativeId(TerminalSession session) {
        if (session.device().demo() || session.sessionId() == null) return null;
        try { return Ordering.canonicalId(session.sessionId()); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    public final class Visit {
        private final UUID session;
        private Draft draft = new Draft(null, Set.of(), "");
        private Attempt pending;
        private Receipt receipt;
        private Phase phase = Phase.DRAFT;
        private boolean eligible;
        private boolean checking;
        private boolean checked;
        private Runnable observer = () -> {};

        private Visit(UUID session) { this.session = session; }

        public Draft draft() { return draft; }
        public Receipt receipt() { return receipt; }
        public Phase phase() { return phase; }
        public boolean available() { return session != null && service != null && service.available(); }
        public boolean writable() { return pending == null && phase != Phase.ACCEPTED; }
        public boolean checking() { return checking; }
        public boolean checked() { return checked; }
        public boolean eligible() { return eligible; }
        public boolean validLength() {
            String comment = draft.comment().strip();
            return comment.codePointCount(0, comment.length()) <= 500;
        }
        public boolean canSend() { return available() && eligible && !checking && writable() && draft.hasFeedback() && validLength(); }

        public void refresh() {
            if (!available() || checking || phase == Phase.SENDING || phase == Phase.ACCEPTED) return;
            checking = true;
            eligible = false;
            checked = false;
            observer.run();
            try {
                service.eligibility(session).whenComplete((value, error) -> ui.execute(() -> applyEligibility(value, error)));
            } catch (RuntimeException error) {
                applyEligibility(null, error);
            }
        }

        private void applyEligibility(Eligibility value, Throwable error) {
            checking = false;
            checked = error == null && value != null && (value.receipt() == null
                    || (value.receipt().reviewId() != null && session.equals(value.receipt().sessionId())));
            if (checked) {
                eligible = value.eligible();
                if (value.receipt() != null) {
                    receipt = value.receipt();
                    pending = null;
                    phase = Phase.ACCEPTED;
                }
            }
            observer.run();
        }

        public void observe(Runnable listener) { observer = listener; }

        public void edit(Draft next) {
            if (!writable()) return;
            draft = next;
            if (phase == Phase.REJECTED) phase = Phase.DRAFT;
            observer.run();
        }

        public void submit() {
            if (!canSend()) return;
            pending = new Attempt(session, UUID.randomUUID(), draft);
            send();
        }

        public void retry() {
            if (phase == Phase.UNCERTAIN && available() && !checking) send();
        }

        private void send() {
            var attempt = pending;
            phase = Phase.SENDING;
            observer.run();
            try {
                service.send(attempt).whenComplete((value, error) -> ui.execute(() -> finish(attempt, value, error)));
            } catch (RuntimeException error) {
                finish(attempt, null, error);
            }
        }

        private void finish(Attempt attempt, Receipt value, Throwable error) {
            if (pending != attempt) return;
            while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
            if (error == null && value != null && value.reviewId() != null && session.equals(value.sessionId())) {
                receipt = value;
                pending = null;
                phase = Phase.ACCEPTED;
            } else if (error instanceof AlreadyExists || error instanceof NotEligible) {
                pending = null;
                phase = Phase.REJECTED;
                refresh();
            } else if (error instanceof Rejected) {
                pending = null;
                phase = Phase.REJECTED;
            } else {
                phase = Phase.UNCERTAIN;
            }
            observer.run();
        }
    }
}
