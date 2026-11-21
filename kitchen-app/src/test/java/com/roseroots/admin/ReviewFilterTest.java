package com.roseroots.admin;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewFilterTest {
    @Test
    void queryEncodesSearchAndKeepsRatingTopicAndFollowUpIndependent() {
        var day = LocalDate.of(2026, 11, 20);
        assertEquals("limit=25&q=food+%26+service&from=2026-11-20&to=2026-11-20&rating=4"
                + "&topic=FOOD_QUALITY&followUp=RESOLVED",
                ReviewsWorkspace.query(" food & service ", day, day, "4", "FOOD_QUALITY", "RESOLVED"));
        assertThrows(IllegalArgumentException.class, () -> ReviewsWorkspace.query("", day.plusDays(1), day, "", "", ""));
    }
}
