package com.roseroots.customer.services.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.state.ReviewState.Topic;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class RestOrderFeedback {
    public static final Set<Topic> ORDER_TOPICS = Set.copyOf(EnumSet.of(Topic.FOOD_QUALITY, Topic.TASTE,
            Topic.TEMPERATURE, Topic.ORDER_ACCURACY, Topic.PREPARATION_SPEED));
    private final SpringBootOrderService rest;

    public RestOrderFeedback(SpringBootOrderService rest) { this.rest = rest; }

    public CompletionStage<Void> send(UUID session, UUID order, UUID item, UUID key, int rating,
                                      Set<Topic> topics, String comment) {
        var body = new JsonObject();
        body.addProperty("rating", rating);
        body.addProperty("comment", comment.strip());
        if (item == null) {
            var tags = new JsonArray();
            topics.stream().sorted().forEach(topic -> tags.add(topic.name()));
            body.add("topics", tags);
        }
        return rest.postFoodFeedback(session, order, item, body, key).thenAccept(response -> {
            var returnedItem = response.get("itemId");
            if (!order.equals(Ordering.canonicalId(response.get("orderId").getAsString()))
                    || (item == null ? !returnedItem.isJsonNull() : !item.equals(Ordering.canonicalId(returnedItem.getAsString())))
                    || rating != response.get("rating").getAsBigDecimal().intValueExact()) {
                throw new IllegalArgumentException("Feedback receipt does not match the submitted target.");
            }
            Ordering.canonicalId(response.get("id").getAsString());
            Instant.parse(response.get("submittedAt").getAsString());
        });
    }
}
