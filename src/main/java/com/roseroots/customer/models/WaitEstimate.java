package com.roseroots.customer.models;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record WaitEstimate(Duration earliest, Duration latest, Instant calculatedAt, Instant validUntil) {
    public WaitEstimate {
        if (earliest.isNegative() || latest.compareTo(earliest) < 0 || !validUntil.isAfter(calculatedAt)) {
            throw new IllegalArgumentException("Invalid estimate range or validity");
        }
    }

    public boolean isCurrent(Instant now) {
        return !now.isBefore(calculatedAt) && now.isBefore(validUntil);
    }

    public record Request(String sessionId, List<Item> items, Ordering.Draft wireDraft) {
        public Request(String sessionId, List<Item> items) { this(sessionId, items, null); }
        public Request {
            items = List.copyOf(items);
        }
    }

    public record Item(String itemId, int quantity, Map<String, Set<String>> options, boolean requiresAllergyReview) {
        public Item {
            options = options.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }
}
