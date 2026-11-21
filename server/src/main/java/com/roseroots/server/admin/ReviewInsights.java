package com.roseroots.server.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;
import static com.roseroots.server.admin.ReviewSource.*;

/** Aggregates the complete filtered result, independently of review pagination. */
public final class ReviewInsights {
    private ReviewInsights() {}

    public static Insights aggregate(List<Review> reviews, ZoneId businessZone, boolean followUpAvailable) {
        long[] stars = new long[6];
        var all = new Bucket();
        var topics = new EnumMap<Topic, Bucket>(Topic.class);
        var days = new TreeMap<java.time.LocalDate, Bucket>();
        var followUp = new EnumMap<FollowUp, Long>(FollowUp.class);
        for (var review : reviews) {
            Integer rating = review.rating();
            all.add(rating);
            if (rating != null) {
                stars[rating]++;
            }
            if (review.submittedAt() != null) {
                days.computeIfAbsent(review.submittedAt().atZone(businessZone).toLocalDate(), ignored -> new Bucket()).add(rating);
            }
            if (review.topics() != null) {
                review.topics().stream().distinct()
                        .forEach(topic -> topics.computeIfAbsent(topic, ignored -> new Bucket()).add(rating));
            }
            if (review.followUp() != null) {
                followUp.merge(review.followUp(), 1L, Long::sum);
            }
        }
        var distribution = new ArrayList<Rating>();
        for (int rating = 5; rating >= 1; rating--) {
            distribution.add(new Rating(rating, stars[rating]));
        }
        var categories = topics.entrySet().stream().map(entry -> {
            var bucket = entry.getValue();
            return new Category(entry.getKey(), bucket.count, bucket.rated, bucket.average());
        }).sorted(java.util.Comparator.comparingLong(Category::count).reversed().thenComparing(Category::topic)).toList();
        var trend = days.entrySet().stream().map(entry -> {
            var bucket = entry.getValue();
            return new Trend(entry.getKey(), bucket.count, bucket.rated, bucket.average());
        }).toList();
        return new Insights(all.count, all.rated, all.average(), stars[4] + stars[5], stars[3],
                stars[1] + stars[2], count(followUp, FollowUp.NEEDS_ATTENTION, followUpAvailable),
                count(followUp, FollowUp.FOLLOWED_UP, followUpAvailable),
                count(followUp, FollowUp.RESOLVED, followUpAvailable), distribution, categories, trend);
    }

    private static Long count(EnumMap<FollowUp, Long> values, FollowUp state, boolean available) {
        return available ? values.getOrDefault(state, 0L) : null;
    }

    private static final class Bucket {
        long count;
        long rated;
        long stars;

        void add(Integer rating) {
            count++;
            if (rating != null) {
                if (rating < 1 || rating > 5) {
                    throw new IllegalArgumentException("Review ratings must be between 1 and 5.");
                }
                rated++;
                stars += rating;
            }
        }

        BigDecimal average() {
            return rated == 0 ? null : BigDecimal.valueOf(stars).divide(BigDecimal.valueOf(rated), 2, RoundingMode.HALF_UP);
        }
    }
}
