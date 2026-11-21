package com.roseroots.kitchen.model;

import java.time.OffsetDateTime;
import java.util.Set;

public record CustomerReview(long id, long sessionId, int rating, OffsetDateTime submittedAt,
                             Set<Topic> topics, String comment) {
    public CustomerReview {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Ratings range from 1 to 5.");
        }
        topics = Set.copyOf(topics);
    }

    public enum Topic {
        FOOD_QUALITY("Food quality", true), TASTE("Taste", true), TEMPERATURE("Temperature", true),
        ORDER_ACCURACY("Order accuracy", true), PREPARATION_SPEED("Preparation speed", true),
        CLEANLINESS("Cleanliness", false), STAFF_HELPFULNESS("Staff helpfulness", false), AMBIENCE("Ambience", false);

        public final String label;
        public final boolean kitchenRelevant;

        Topic(String label, boolean kitchenRelevant) {
            this.label = label;
            this.kitchenRelevant = kitchenRelevant;
        }
    }
}
