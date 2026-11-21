package com.roseroots.customer.models;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class Ordering {
    private Ordering() {}

    public static UUID canonicalId(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Expected canonical UUID");
        return id;
    }

    public record Line(UUID clientLineId, String menuItemId, int quantity,
                       Map<String, Set<String>> selections, AllergyDeclaration allergy,
                       String instructions) {
        public Line {
            selections = selections.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }

    public record Draft(UUID sessionId, ServiceMode mode, AllergyDeclaration visitAllergy, List<Line> lines) {
        public Draft { lines = List.copyOf(lines); }
    }

    public record Option(UUID groupId, UUID optionId, String name, long unitAdjustmentMinor) {}

    public record Price(UUID clientLineId, UUID menuItemId, String name, int quantity,
                        long baseUnitMinor, List<Option> options, long extendedMinor) {
        public Price { options = List.copyOf(options); }
    }

    public record Totals(long subtotalMinor, long serviceMinor, long taxMinor, long totalMinor, String currency) {}

    public record Quote(UUID id, UUID sessionId, Instant expiresAt, List<Price> lines, Totals totals) {
        public Quote { lines = List.copyOf(lines); }
    }

    public enum Status { RECEIVED, PREPARING, READY, SERVED, COLLECTED, CANCELLED }

    public enum Review {
        NOT_REQUIRED, PENDING_ALLERGY_REVIEW, CLARIFICATION_REQUESTED, DEFERRED,
        CUSTOMER_APPROVAL_REQUIRED, APPROVED_FOR_PREPARATION, DECLINED
    }

    public record AcceptedLine(UUID id, Price pricing, AllergyDeclaration allergy, String instructions,
                               Status preparation, Review review, boolean billable, String reviewMessage) {}

    public record Accepted(UUID id, UUID sessionId, ServiceMode mode, Status status, Instant acceptedAt,
                           AllergyDeclaration visitAllergy, List<AcceptedLine> lines, Totals totals) {
        public Accepted { lines = List.copyOf(lines); }
    }
}
