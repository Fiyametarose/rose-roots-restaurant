package com.roseroots.server.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Manager read projection to be implemented by the shared review owner. */
public interface ReviewSource {
    Page reviews(Query query);

    enum Topic { FOOD_QUALITY, TASTE, TEMPERATURE, ORDER_ACCURACY, PREPARATION_SPEED,
        CLEANLINESS, STAFF_HELPFULNESS, AMBIENCE }
    enum FollowUp { NEEDS_ATTENTION, FOLLOWED_UP, RESOLVED }

    record Query(String search, LocalDate from, LocalDate to, Integer rating, Topic topic,
                 FollowUp followUp, String cursor, int limit) {}
    record Review(UUID id, UUID sessionId, String serviceReference, Instant submittedAt,
                  Integer rating, List<Topic> topics, String comment, FollowUp followUp, String internalResponse) {}
    record Rating(int stars, long count) {}
    record Category(Topic topic, long count, long ratedCount, java.math.BigDecimal average) {}
    record Trend(LocalDate date, long count, long ratedCount, java.math.BigDecimal average) {}
    record Insights(long total, long rated, java.math.BigDecimal average, long positive, long neutral,
                    long negative, Long needsAttention, Long followedUp, Long resolved,
                    List<Rating> distribution, List<Category> categories, List<Trend> trend) {}
    record Page(Insights insights, List<Review> items, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }
}
