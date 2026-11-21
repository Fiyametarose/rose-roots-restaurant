package com.roseroots.customer.services;

import com.roseroots.customer.models.Ordering;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface OrderService {
    /** The integration layer enables this service after establishing backend compatibility and authentication. */
    default boolean available() { return true; }

    CompletionStage<Ordering.Quote> quote(Ordering.Draft draft);

    CompletionStage<Ordering.Accepted> submit(Ordering.Draft draft, UUID quoteId, UUID idempotencyKey);

    default CompletionStage<Ordering.Accepted> order(UUID session, UUID orderId) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    static OrderService unavailable() {
        return new OrderService() {
            @Override public boolean available() { return false; }

            @Override public CompletionStage<Ordering.Quote> quote(Ordering.Draft draft) {
                throw new IllegalStateException("Ordering is disconnected");
            }

            @Override public CompletionStage<Ordering.Accepted> submit(Ordering.Draft draft, UUID quoteId, UUID key) {
                throw new IllegalStateException("Ordering is disconnected");
            }
        };
    }

    record Violation(String pointer, String code, String message) {}

    final class Problem extends RuntimeException {
        private final String code;
        private final boolean retrySameKey;
        private final java.util.List<Violation> violations;

        public Problem(String code, boolean retrySameKey) {
            this(code, retrySameKey, java.util.List.of());
        }

        public Problem(String code, boolean retrySameKey, java.util.List<Violation> violations) {
            super(code);
            this.violations = java.util.List.copyOf(violations);
            this.code = code;
            this.retrySameKey = retrySameKey;
        }

        public String code() { return code; }
        public java.util.List<Violation> violations() { return violations; }
        public boolean retrySameKey() { return retrySameKey; }
    }
}
