package com.roseroots.customer.models;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Payment {
    private Payment() {}

    public enum Status { PENDING, SUCCEEDED, FAILED, CANCELLED }

    public record Balance(UUID sessionId, long billVersion, long payable, long settled, long outstanding,
                          Set<String> blocked, UUID openAttempt, UUID settledAttempt, boolean cashAvailable) {
        public Balance(UUID sessionId, long billVersion, long payable, long settled, long outstanding,
                       Set<String> blocked, UUID openAttempt, boolean cashAvailable) {
            this(sessionId, billVersion, payable, settled, outstanding, blocked, openAttempt, null, cashAvailable);
        }
        public Balance { blocked = Set.copyOf(blocked); }
        public boolean canStart() {
            return cashAvailable && outstanding > 0 && blocked.isEmpty() && openAttempt == null;
        }
    }

    public record Attempt(UUID id, UUID sessionId, Status status, long billVersion, long version,
                          long amount, boolean cancellationRequested, UUID receiptId) {}

    public record Line(UUID orderId, UUID itemId, Ordering.Price price) {}

    public record Receipt(UUID id, UUID sessionId, UUID paymentId, long amount, Instant verifiedAt,
                          List<Line> lines) {
        public Receipt { lines = List.copyOf(lines); }
    }
}
