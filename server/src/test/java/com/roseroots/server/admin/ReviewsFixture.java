package com.roseroots.server.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static com.roseroots.server.admin.ReviewSource.*;

@TestConfiguration(proxyBeanMethods = false)
public class ReviewsFixture {
    @Bean
    ReviewSource fixtureReviews() {
        var reviews = List.of(
                review(11, 5, "Table 07", "Great food and a welcoming dining room.", FollowUp.RESOLVED,
                        Topic.FOOD_QUALITY, Topic.AMBIENCE),
                review(12, 4, "Pickup P-018", "The order was ready promptly.", FollowUp.FOLLOWED_UP,
                        Topic.PREPARATION_SPEED, Topic.STAFF_HELPFULNESS),
                review(13, 2, "Table 03", "Our table needed cleaning when we arrived.", FollowUp.NEEDS_ATTENTION,
                        Topic.CLEANLINESS),
                review(14, null, null, null, null, Topic.FOOD_QUALITY));
        return query -> {
            var matches = reviews.stream().filter(review -> matches(review, query)).toList();
            int offset = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
            int end = Math.min(offset + query.limit(), matches.size());
            return new Page(ReviewInsights.aggregate(matches, ZoneOffset.UTC, true),
                    matches.subList(offset, end), end < matches.size() ? String.valueOf(end) : null);
        };
    }

    private static Review review(long id, Integer rating, String reference, String comment, FollowUp state, Topic... topics) {
        return new Review(new UUID(0, id), new UUID(0, 100 + id), reference,
                Instant.parse("2026-11-20T18:30:00Z").minusSeconds((id - 11) * 86400), rating,
                List.of(topics), comment, state, null);
    }

    private static boolean matches(Review review, Query query) {
        String searchable = (review.id() + " " + review.serviceReference() + " " + review.comment()).toLowerCase(Locale.ROOT);
        var date = review.submittedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return searchable.contains(query.search().toLowerCase(Locale.ROOT))
                && (query.from() == null || !date.isBefore(query.from()))
                && (query.to() == null || !date.isAfter(query.to()))
                && (query.rating() == null || query.rating().equals(review.rating()))
                && (query.topic() == null || review.topics().contains(query.topic()))
                && (query.followUp() == null || review.followUp() == query.followUp());
    }
}
