package com.roseroots.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import static com.roseroots.admin.CatalogControls.*;
import static com.roseroots.admin.OperationsPresentation.classify;
import static com.roseroots.admin.OperationsPresentation.timestamp;

final class FoodFeedbackWorkspace extends VBox {
    private final AdminApi api;
    private final Runnable expired;
    private final ComboBox<String> type = new ComboBox<>();
    private final ComboBox<String> rating = new ComboBox<>();
    private final TextField dish = new TextField();
    private final DatePicker from = new DatePicker();
    private final DatePicker through = new DatePicker();
    private final VBox reviews = new VBox(8);
    private final VBox summaries = new VBox(6);
    private final javafx.scene.control.Label status = label("", "muted");
    private int offset;
    private final javafx.scene.control.Button previous = button("Previous", () -> fetch(offset - 25));
    private final javafx.scene.control.Button next = button("Next", () -> fetch(offset + 25));
    private long generation;
    private String query = "";

    FoodFeedbackWorkspace(AdminApi api, Runnable expired) {
        super(12);
        this.api = api;
        this.expired = expired;
        type.getItems().addAll("Dish and order feedback", "Dish only", "Order only");
        type.getSelectionModel().selectFirst();
        rating.getItems().addAll("Any rating", "1", "2", "3", "4", "5");
        rating.getSelectionModel().selectFirst();
        dish.setPromptText("Historical dish name");
        dish.setOnAction(event -> refresh());
        previous.setDisable(true);
        next.setDisable(true);
        var filters = new FlowPane(10, 8, field("Review type", type), field("Dish name", dish),
                field("Rating", rating), field("From", from), field("Through", through));
        getChildren().addAll(panel("Filters", content(filters, button("Apply filters", this::refresh), status)),
                panel("Dish ratings", content(summaries)),
                panel("Dish and order reviews", content(new FlowPane(8, 6, previous, next), reviews)));
    }

    void refresh() {
        if (from.getValue() != null && through.getValue() != null && from.getValue().isAfter(through.getValue())
                || dish.getText().strip().length() > 120) {
            status.setText("Check the date range and dish name.");
            return;
        }
        String selectedType = switch (type.getSelectionModel().getSelectedIndex()) {
            case 1 -> "DISH";
            case 2 -> "ORDER";
            default -> "ALL";
        };
        query = "type=" + selectedType + "&dish=" + URLEncoder.encode(dish.getText().strip(), StandardCharsets.UTF_8)
                + (rating.getSelectionModel().getSelectedIndex() > 0 ? "&rating=" + rating.getValue() : "")
                + (from.getValue() == null ? "" : "&from=" + from.getValue())
                + (through.getValue() == null ? "" : "&to=" + through.getValue());
        fetch(0);
    }

    private void fetch(int start) {
        String request = query + "&offset=" + start;
        long requestGeneration = ++generation;
        previous.setDisable(true);
        next.setDisable(true);
        status.setText("Loading food feedback…");
        AdminWorkspace.background(() -> api.foodFeedback(request), (page, failure) -> {
            if (requestGeneration != generation) return;
            if (failure != null) {
                if (classify(failure) == OperationsPresentation.Failure.SESSION) expired.run();
                status.setText("Could not load food feedback. Apply filters to retry.");
                return;
            }
            offset = start;
            previous.setDisable(offset == 0);
            next.setDisable(!page.get("hasMore").getAsBoolean());
            status.setText("");
            summaries.getChildren().clear();
            reviews.getChildren().clear();
            for (var element : page.getAsJsonArray("dishes")) {
                var row = element.getAsJsonObject();
                summaries.getChildren().add(label(text(row, "dish") + " · "
                        + String.format(Locale.ENGLISH, "%.1f / 5", row.get("average").getAsDouble())
                        + " · " + row.get("count").getAsLong() + " ratings", "muted"));
            }
            if (summaries.getChildren().isEmpty()) summaries.getChildren().add(label("No dish ratings match.", "muted"));
            for (var element : page.getAsJsonArray("reviews")) {
                var row = element.getAsJsonObject();
                boolean isDish = "DISH".equals(text(row, "type"));
                var heading = label((isDish ? text(row, "dish") : "Order feedback") + " · ★ "
                        + row.get("rating").getAsInt() + " / 5", "field-label");
                var card = new VBox(4, heading, label((isDish ? "Dish" : "Order") + " · Order #"
                        + text(row, "orderReference") + " · " + timestamp(row, "submittedAt"), "muted"));
                String comment = text(row, "comment");
                if (!comment.isBlank()) card.getChildren().add(label(comment, "muted"));
                var tags = row.getAsJsonArray("topics");
                if (!tags.isEmpty()) card.getChildren().add(label(tags.asList().stream()
                        .map(tag -> tag.getAsString().replace('_', ' ').toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.joining(" · ")), "muted"));
                card.getStyleClass().add("panel");
                reviews.getChildren().add(card);
            }
            if (reviews.getChildren().isEmpty()) reviews.getChildren().add(label("No reviews match these filters.", "muted"));
        });
    }
}
