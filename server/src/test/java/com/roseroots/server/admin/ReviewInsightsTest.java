package com.roseroots.server.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static com.roseroots.server.admin.ReviewSource.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewInsightsTest {
    private final ReviewSource source = new ReviewsFixture().fixtureReviews();

    @Test
    void summaryExcludesMissingRatingsAndKeepsTaggedDenominators() {
        var insights = source.reviews(new Query("", null, null, null, null, null, null, 1)).insights();
        assertEquals(4, insights.total());
        assertEquals(3, insights.rated());
        assertEquals(new BigDecimal("3.67"), insights.average());
        assertEquals(2, insights.positive());
        assertEquals(0, insights.neutral());
        assertEquals(1, insights.negative());
        assertEquals(1L, insights.needsAttention());
        assertEquals(1L, insights.followedUp());
        assertEquals(1L, insights.resolved());
        assertEquals(java.util.List.of(5, 4, 3, 2, 1), insights.distribution().stream().map(Rating::stars).toList());
        assertEquals(new Category(Topic.FOOD_QUALITY, 2, 1, new BigDecimal("5.00")), insights.categories().getFirst());
        assertNull(insights.trend().getFirst().average());
    }

    @Test
    void filteredSummarySpansPagesAndRealEmptyReviewsHaveNoAverage() {
        var filtered = source.reviews(new Query("pickup", LocalDate.of(2026, 11, 19),
                LocalDate.of(2026, 11, 20), 4, Topic.PREPARATION_SPEED, FollowUp.FOLLOWED_UP, null, 1));
        assertEquals(1, filtered.items().size());
        assertEquals(1, filtered.insights().total());
        assertEquals("Pickup P-018", filtered.items().getFirst().serviceReference());
        var page = source.reviews(new Query("", null, null, null, null, null, "1", 1));
        assertEquals(4, page.insights().total());
        assertEquals("2", page.nextCursor());
        var empty = ReviewInsights.aggregate(java.util.List.of(), ZoneOffset.UTC, false);
        assertEquals(0, empty.total());
        assertNull(empty.average());
        assertNull(empty.resolved());
    }
}
