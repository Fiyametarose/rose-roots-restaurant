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
        cards.getStyleClass().add("ticket-list");
        var scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(new FlowPane(8, 8, topic, rating, refresh), status, summary, scroll);
    }

    public void refresh() {
        if (stopped || refresh.isDisabled()) return;
        refresh.setDisable(true);
        status.setText("Loading customer reviews…");
        rest.reviews().whenComplete((reviews, failure) -> Platform.runLater(() -> {
            if (stopped) return;
            refresh.setDisable(false);
            snapshot = failure == null ? reviews : List.of();
            status.setText(failure == null ? "" : "Customer reviews could not be loaded.");
            render();
        }));
    }

    public void stop() { stopped = true; }

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
