package com.roseroots.customer.models;

import java.time.Duration;
import java.time.Instant;

public record WaitEstimate(Duration earliest, Duration latest, Instant calculatedAt, Instant validUntil) {
    public WaitEstimate {
        if (earliest.isNegative() || latest.compareTo(earliest) < 0 || !validUntil.isAfter(calculatedAt)) {
            throw new IllegalArgumentException("Invalid estimate range or validity");
        }
    }

    public boolean isCurrent(Instant now) {
        return !now.isBefore(calculatedAt) && now.isBefore(validUntil);
    }
}
