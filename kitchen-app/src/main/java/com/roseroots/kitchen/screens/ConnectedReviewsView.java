package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.model.CustomerReview.Topic;
import com.roseroots.kitchen.service.http.SpringBootKitchenService;
import com.roseroots.kitchen.service.http.SpringBootKitchenService.ConnectedReview;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class ConnectedReviewsView extends VBox {
    private final SpringBootKitchenService rest;
    private final List<Topic> topics = Arrays.stream(Topic.values()).filter(topic -> topic.kitchenRelevant).toList();
    private final ComboBox<String> topic = new ComboBox<>();
    private final ComboBox<String> rating = new ComboBox<>();
    private final Label status = label("", "connection-state");
    private final Label summary = label("", "lane-caption");
    private final VBox cards = new VBox(8);
    private final Button refresh = new Button("Refresh reviews");
    private final ComboBox<String> source = new ComboBox<>();
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private int offset;
    private List<ConnectedReview> snapshot = List.of();
    private boolean stopped;

    public ConnectedReviewsView(SpringBootKitchenService rest) {
        this.rest = rest;
        setSpacing(8);
        topic.getItems().add("All food topics");
        topic.getItems().addAll(topics.stream().map(value -> value.label).toList());
        topic.getSelectionModel().selectFirst();
        topic.setOnAction(event -> render());
        rating.getItems().addAll("All ratings", "1 star", "2 stars", "3 stars", "4 stars", "5 stars", "Unrated");
        rating.getSelectionModel().selectFirst();
        rating.setOnAction(event -> render());
        refresh.setOnAction(event -> refresh());
        source.getItems().addAll("Restaurant feedback", "Dish and order feedback");
        source.getSelectionModel().selectFirst();
        source.setOnAction(event -> { offset = 0; refresh(); });
        previous.setOnAction(event -> { offset = Math.max(0, offset - 25); refresh(); });
        next.setOnAction(event -> { offset += 25; refresh(); });
        cards.getStyleClass().add("ticket-list");
        var scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(new FlowPane(8, 8, source, topic, rating, refresh, previous, next), status, summary, scroll);
    }

    public void refresh() {
        if (stopped || refresh.isDisabled()) return;
        refresh.setDisable(true);
        source.setDisable(true);
        previous.setDisable(true);
        next.setDisable(true);
        boolean food = source.getSelectionModel().getSelectedIndex() == 1;
        for (var field : List.of(previous, next)) {
            field.setVisible(food);
            field.setManaged(food);
        }
        for (var field : List.of(topic, rating)) {
            field.setVisible(!food);
            field.setManaged(!food);
        }
        status.setText("Loading customer reviews…");
        if (food) {
            rest.foodFeedback(offset).whenComplete((page, failure) -> Platform.runLater(() -> {
                if (stopped) return;
                refresh.setDisable(false);
                source.setDisable(false);
                previous.setDisable(offset == 0);
                cards.getChildren().clear();
                summary.setText("");
                status.setText(failure == null ? "" : "Dish and order feedback could not be loaded.");
                if (failure != null) return;
                next.setDisable(!page.get("hasMore").getAsBoolean());
                for (var element : page.getAsJsonArray("reviews")) {
                    var row = element.getAsJsonObject();
                    String subject = row.get("dish").isJsonNull() ? "Order feedback" : "Dish feedback · " + row.get("dish").getAsString();
                    var card = new VBox(5, label(subject, "metadata"),
                            label("★ " + row.get("rating").getAsInt() + " / 5 · Order #"
                                    + row.get("orderReference").getAsString() + " · "
                                    + date(java.time.Instant.parse(row.get("submittedAt").getAsString())), "review-stars"));
                    String tags = row.getAsJsonArray("topics").asList().stream()
                            .map(value -> Topic.valueOf(value.getAsString()).label).collect(Collectors.joining(" · "));
                    if (!tags.isBlank()) card.getChildren().add(label(tags, "metadata"));
                    String comment = row.get("comment").getAsString();
                    if (!comment.isBlank()) card.getChildren().add(label(comment, "review-comment"));
                    card.getStyleClass().addAll("ticket", "review-card");
                    cards.getChildren().add(card);
                }
                if (cards.getChildren().isEmpty()) status.setText("No dish or order feedback.");
            }));
            return;
        }
        rest.reviews().whenComplete((reviews, failure) -> Platform.runLater(() -> {
            if (stopped) return;
            refresh.setDisable(false);
            source.setDisable(false);
            snapshot = failure == null ? reviews : List.of();
            status.setText(failure == null ? "" : "Customer reviews could not be loaded.");
            render();
        }));
    }

    public void stop() { stopped = true; }

    private static String date(java.time.Instant time) {
        return DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(time);
    }

    private void render() {
        int stars = rating.getSelectionModel().getSelectedIndex();
        int selectedTopic = topic.getSelectionModel().getSelectedIndex();
        var filtered = snapshot.stream()
                .filter(row -> stars <= 0 || (stars == 6 ? row.rating() == null : row.rating() != null && row.rating() == stars))
                .filter(row -> selectedTopic <= 0 || row.topics().contains(topics.get(selectedTopic - 1))).toList();
        var average = filtered.stream().filter(row -> row.rating() != null).mapToInt(ConnectedReview::rating).average();
        summary.setText(filtered.size() + " reviews" + (average.isPresent()
                ? String.format(Locale.ENGLISH, " · Average %.1f / 5", average.getAsDouble()) : ""));
        cards.getChildren().clear();
        for (var review : filtered) {
            var date = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());
            var card = new VBox(5, label((review.rating() == null ? "Unrated" : review.rating() + " / 5")
                            + " · " + date.format(review.submittedAt()), "review-stars"),
                    label(review.topics().stream().map(value -> value.label).collect(Collectors.joining(" · ")), "metadata"));
            if (!review.comment().isBlank()) card.getChildren().add(label(review.comment(), "review-comment"));
            card.getStyleClass().addAll("ticket", "review-card");
            cards.getChildren().add(card);
        }
        if (filtered.isEmpty() && status.getText().isEmpty()) cards.getChildren().add(label("No reviews match these filters.", "empty"));
    }

    private static Label label(String text, String style) {
        var label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }
}
