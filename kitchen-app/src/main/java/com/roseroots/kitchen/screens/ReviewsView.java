package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.model.CustomerReview;
import com.roseroots.kitchen.model.CustomerReview.Topic;
import com.roseroots.kitchen.model.ReviewQuery;
import com.roseroots.kitchen.service.KitchenReviewService;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class ReviewsView extends VBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private final KitchenReviewService service;
    private final VBox cards = new VBox(8);
    private final Label count = label("", "priority-stat-count");
    private final Label average = label("", "priority-stat-count");
    private final ComboBox<String> rating = new ComboBox<>();
    private final ComboBox<String> topic = new ComboBox<>();
    private final java.util.List<Topic> topics = Arrays.stream(Topic.values()).filter(value -> value.kitchenRelevant).toList();

    public ReviewsView(KitchenReviewService service) {
        this.service = service;
        setSpacing(10);
        rating.setId("review-rating");
        rating.getItems().addAll("All ratings", "1 star", "2 stars", "3 stars", "4 stars", "5 stars");
        rating.getSelectionModel().selectFirst();
        rating.setOnAction(event -> refresh());
        topic.setId("review-topic");
        topic.getItems().add("All food topics");
        topic.getItems().addAll(topics.stream().map(value -> value.label).toList());
        topic.getSelectionModel().selectFirst();
        topic.setOnAction(event -> refresh());
        Label ratingLabel = label("Rating", "item-name");
        ratingLabel.setLabelFor(rating);
        Label topicLabel = label("Topic", "item-name");
        topicLabel.setLabelFor(topic);
        FlowPane filters = new FlowPane(8, 5, ratingLabel, rating, topicLabel, topic);
        filters.getStyleClass().add("filters");
        HBox summary = new HBox(8, metric("Average rating", average), metric("Reviews shown", count));
        VBox heading = new VBox(3, label("Customer reviews", "lane-title"),
                label("Kitchen feedback · Read only", "lane-caption"));
        heading.getStyleClass().add("lane-heading");
        cards.getStyleClass().add("ticket-list");
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox results = new VBox(heading, scroll);
        results.getStyleClass().add("lane");
        VBox.setVgrow(results, Priority.ALWAYS);
        getChildren().addAll(summary, filters, results);
    }

    public void refresh() {
        int ratingIndex = rating.getSelectionModel().getSelectedIndex();
        int topicIndex = topic.getSelectionModel().getSelectedIndex();
        var result = ReviewQuery.filter(service.kitchenReviews(),
                ratingIndex > 0 ? ratingIndex : null, topicIndex > 0 ? topics.get(topicIndex - 1) : null);
        count.setText(Integer.toString(result.count()));
        average.setText(result.average().isPresent()
                ? String.format(Locale.ENGLISH, "%.1f / 5", result.average().getAsDouble()) : "—");
        cards.getChildren().clear();
        result.reviews().forEach(review -> cards.getChildren().add(card(review)));
        if (result.count() == 0) {
            cards.getChildren().add(label("No reviews match these filters", "empty"));
        }
    }

    private static VBox card(CustomerReview review) {
        String stars = "★".repeat(review.rating()) + "☆".repeat(5 - review.rating());
        FlowPane heading = new FlowPane(8, 4, label(stars, "review-stars"),
                label(review.rating() + " / 5", "item-name"), label(DATE.format(review.submittedAt()), "metadata"));
        Label comment = label(review.comment(), "review-comment");
        FlowPane tags = new FlowPane(5, 4);
        review.topics().stream().filter(topic -> topic.kitchenRelevant).sorted()
                .forEach(topic -> tags.getChildren().add(label(topic.label, "normal")));
        VBox card = new VBox(5, heading, comment, tags);
        card.getStyleClass().addAll("ticket", "review-card");
        return card;
    }

    private static VBox metric(String name, Label value) {
        VBox metric = new VBox(2, value, label(name, "metadata"));
        metric.getStyleClass().add("ticket");
        metric.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(metric, Priority.ALWAYS);
        return metric;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
