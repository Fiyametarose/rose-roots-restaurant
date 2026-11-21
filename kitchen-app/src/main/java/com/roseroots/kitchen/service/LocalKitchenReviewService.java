package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.CustomerReview;
import com.roseroots.kitchen.model.CustomerReview.Topic;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public final class LocalKitchenReviewService implements KitchenReviewService {
    private final List<CustomerReview> reviews;

    public LocalKitchenReviewService(List<CustomerReview> reviews) {
        this.reviews = reviews.stream().filter(review -> review.topics().stream().anyMatch(topic -> topic.kitchenRelevant))
                .toList();
    }

    @Override
    public List<CustomerReview> kitchenReviews() {
        return reviews;
    }

    public static LocalKitchenReviewService sample(OffsetDateTime now) {
        return new LocalKitchenReviewService(List.of(
                new CustomerReview(71, 41, 5, now.minusMinutes(28), Set.of(Topic.TASTE, Topic.TEMPERATURE),
                        "The Doro Wat arrived hot, and the spice level was exactly what I asked for."),
                new CustomerReview(72, 42, 3, now.minusHours(2), Set.of(Topic.TASTE, Topic.TEMPERATURE),
                        "Lovely seasoning on the grilled fish, but it had cooled by the time we received it."),
                new CustomerReview(73, 43, 4, now.minusDays(1), Set.of(Topic.FOOD_QUALITY, Topic.PREPARATION_SPEED),
                        "The sambusa were crisp and fresh. Our mains took longer than expected."),
                new CustomerReview(74, 44, 5, now.minusDays(1).minusHours(2), Set.of(Topic.ORDER_ACCURACY, Topic.TASTE),
                        "Our takeaway was packed correctly, including the extra injera and the sauce on the side."),
                new CustomerReview(75, 45, 4, now.minusDays(2), Set.of(Topic.PREPARATION_SPEED, Topic.ORDER_ACCURACY),
                        "Everything we ordered was there, and the takeaway was ready when we arrived."),
                new CustomerReview(76, 46, 5, now.minusDays(2).minusHours(1), Set.of(Topic.AMBIENCE),
                        "The dining room was quiet and comfortable.")));
    }
}
