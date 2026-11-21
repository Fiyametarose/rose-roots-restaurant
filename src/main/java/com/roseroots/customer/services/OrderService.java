package com.roseroots.customer.services;

import com.roseroots.customer.models.Ordering;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface OrderService {
    /** The integration layer enables this service after establishing backend compatibility and authentication. */
    default boolean available() { return true; }

    CompletionStage<Ordering.Quote> quote(Ordering.Draft draft);

    CompletionStage<Ordering.Accepted> submit(Ordering.Draft draft, UUID quoteId, UUID idempotencyKey);

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

    final class Problem extends RuntimeException {
        private final String code;
        private final boolean retrySameKey;

        public Problem(String code, boolean retrySameKey) {
            super(code);
            this.code = code;
            this.retrySameKey = retrySameKey;
        }

        public String code() { return code; }
        public boolean retrySameKey() { return retrySameKey; }
    }
}
