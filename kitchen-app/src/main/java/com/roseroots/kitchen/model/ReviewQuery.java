package com.roseroots.kitchen.model;

import com.roseroots.kitchen.model.CustomerReview.Topic;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

public final class ReviewQuery {
    private ReviewQuery() {}

    public record Results(List<CustomerReview> reviews, OptionalDouble average) {
        public int count() {
            return reviews.size();
        }
    }

    public static Results filter(List<CustomerReview> kitchenReviews, Integer rating, Topic topic) {
        var displayed = kitchenReviews.stream()
                .filter(review -> rating == null || review.rating() == rating)
                .filter(review -> topic == null || review.topics().contains(topic))
                .sorted(Comparator.comparing((CustomerReview review) -> review.submittedAt().toInstant()).reversed()
                        .thenComparingLong(CustomerReview::id))
                .toList();
        return new Results(displayed, displayed.stream().mapToInt(CustomerReview::rating).average());
    }
}
