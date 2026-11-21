package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import static com.roseroots.admin.CatalogControls.*;
import static com.roseroots.admin.OperationsPresentation.*;

final class ReviewsWorkspace extends VBox {
    private final AdminApi api;
    private final Runnable expired;
    private final Runnable top;
    private final VBox view = new VBox(14);
    private final VBox list = new VBox(14);
    private final VBox charts = new VBox(14);
    private final Label status = label("", "muted");
    private final TextField search = new TextField();
    private final DatePicker from = datePicker();
    private final DatePicker to = datePicker();
    private final ComboBox<Choice> rating = filter("All ratings", "5", "4", "3", "2", "1");
    private final ComboBox<Choice> topic = filter("All categories", "FOOD_QUALITY", "TASTE", "TEMPERATURE",
            "ORDER_ACCURACY", "PREPARATION_SPEED", "CLEANLINESS", "STAFF_HELPFULNESS", "AMBIENCE");
    private final ComboBox<Choice> followUp = filter("All follow-up states", "NEEDS_ATTENTION", "FOLLOWED_UP", "RESOLVED");
    private final TableView<JsonObject> table = table();
    private final javafx.scene.control.Button previous = button("Previous", this::previous);
    private final javafx.scene.control.Button next = button("Next", this::next);
    private final Label pageIndicator = label("", "muted");
    private final ArrayList<String> cursors = new ArrayList<>();
    private String query;
    private String nextCursor = "";
    private int page;
    private long generation;

    ReviewsWorkspace(AdminApi api, Runnable expired, Runnable top) {
        super(14);
        this.api = api;
        this.expired = expired;
        this.top = top;
        getChildren().addAll(label("Reviews & Insights", "heading"),
                label("Track customer satisfaction, explore feedback, and review follow-up status.", "muted"), view);
        search.setPromptText("Comment, review ID, table or pickup");
        search.setOnAction(event -> apply());
        var filters = new FlowPane(10, 8, field("Search reviews", search), field("From", from), field("Through", to),
                field("Star rating", rating), field("Feedback category", topic), field("Follow-up", followUp));
        var clear = button("Clear filters", () -> {
            search.clear();
            from.setValue(null);
            to.setValue(null);
            for (var combo : List.of(rating, topic, followUp)) {
                combo.getSelectionModel().selectFirst();
            }
            apply();
        });
        clear.getStyleClass().add("secondary");
        var refresh = button("Refresh", this::apply);
        refresh.getStyleClass().add("secondary");
        table.getColumns().setAll(List.of(column("Review ID", 180, row -> value(row, "id")),
                column("Submitted", 155, row -> timestamp(row, "submittedAt")),
                column("Stars", 74, ReviewsWorkspace::stars),
                column("Feedback tags", 171, ReviewsWorkspace::tags),
                column("Comment", 210, row -> text(row, "comment").isBlank() ? "No comment" : text(row, "comment")),
                column("Table / pickup", 111, row -> value(row, "serviceReference")),
                column("Follow-up", 110, row -> state(text(row, "followUp")))));
        table.getColumns().forEach(column -> column.setMinWidth(Math.min(column.getPrefWidth(), 72)));
        table.widthProperty().addListener((observable, old, width) -> table.setColumnResizePolicy(
                width.doubleValue() < 800 ? TableView.UNCONSTRAINED_RESIZE_POLICY
                        : TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN));
        table.setPrefHeight(330);
        var details = button("View review", () -> detail(table.getSelectionModel().getSelectedItem()));
        details.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.setOnMouseClicked(event -> { if (event.getClickCount() == 2) { details.fire(); } });
        list.getChildren().addAll(panel("Filters", content(filters, new FlowPane(8, 8,
                        button("Apply filters", this::apply), clear, refresh), status)), charts,
                panel("Recent Reviews", content(new FlowPane(8, 8, previous, next, pageIndicator, details), table)));
        view.getChildren().setAll(list);
        apply();
    }

    private void apply() {
        try {
            query = query(search.getText(), date(from), date(to),
                    rating.getValue().code(), topic.getValue().code(), followUp.getValue().code());
            cursors.clear();
            cursors.add("");
            fetch(0);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            status.setText("Enter valid dates in order and keep search at 120 characters or fewer.");
        }
    }

    private void previous() {
        if (page > 0) {
            fetch(page - 1);
        }
    }

    private void next() {
        if (!nextCursor.isBlank()) {
            int target = page + 1;
            if (cursors.size() == target) {
                cursors.add(nextCursor);
            } else {
                cursors.set(target, nextCursor);
            }
            fetch(target);
        }
    }

    private void fetch(int target) {
        long request = ++generation;
        String parameters = query + (cursors.get(target).isEmpty() ? "" : "&cursor=" + encode(cursors.get(target)));
        previous.setDisable(true);
        next.setDisable(true);
        pageIndicator.setText("");
        nextCursor = "";
        status.setText("Loading customer feedback…");
        table.getItems().clear();
        var progress = new ProgressIndicator();
        progress.setMaxSize(28, 28);
        table.setPlaceholder(progress);
        insights(null);
        AdminWorkspace.background(() -> api.reviews(parameters), (data, failure) -> {
            if (request != generation || getScene() == null) {
                return;
            }
            if (failure != null) {
                String message = failureMessage(failure);
                status.setText(message);
                table.setPlaceholder(label("No reviews loaded.", "muted"));
                return;
            }
            if (!(data.get("items") instanceof JsonArray)) {
                status.setText("The server returned an incomplete review response.");
                table.setPlaceholder(label("No reviews loaded.", "muted"));
                return;
            }
            page = target;
            nextCursor = text(data, "nextCursor");
            table.getSortOrder().clear();
            table.getItems().setAll(rows(data, "items"));
            table.setPlaceholder(label("No reviews match these filters.", "muted"));
            status.setText(table.getItems().isEmpty() ? "No matching reviews." : "Connected to shared reviews.");
            pageIndicator.setText("Page " + (page + 1));
            previous.setDisable(page == 0);
            next.setDisable(nextCursor.isBlank());
            insights(object(data, "insights"));
        });
    }

    private String failureMessage(Exception failure) {
        if (failure instanceof AdminApi.Rejected rejected && rejected.status == 503
                && rejected.code.equals("REVIEW_SOURCE_UNAVAILABLE")) {
            return "Customer reviews are unavailable until the shared review service is connected.";
        }
        return switch (classify(failure)) {
            case SESSION -> {
                expired.run();
                yield "Your manager session ended.";
            }
            case DISCONNECTED -> "Disconnected. Check the server connection and refresh.";
            case FILTERS -> "The server rejected these filters. Check the values.";
            default -> "The server could not load reviews. Try again.";
        };
    }

    private void insights(JsonObject data) {
        var metrics = new FlowPane(9, 8, metric("Average rating", average(data)),
                metric("Submitted reviews", value(data, "total")),
                metric("Positive / neutral / negative", counts(data, "positive", "neutral", "negative")),
                metric("Needs attention", value(data, "needsAttention")),
                metric("Followed up / resolved", counts(data, "followedUp", "resolved")));
        var distribution = new VBox(6);
        for (var row : rows(data, "distribution")) {
            var bar = new ProgressBar(fraction(row, data));
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(bar, Priority.ALWAYS);
            var line = new HBox(10, label(value(row, "stars") + " stars", "field-label"),
                    bar, label(value(row, "count"), "muted"));
            line.setAlignment(Pos.CENTER_LEFT);
            distribution.getChildren().add(line);
        }
        if (distribution.getChildren().isEmpty()) {
            distribution.getChildren().add(label(UNAVAILABLE, "muted"));
        } else {
            distribution.getChildren().add(label("Based on " + value(data, "rated") + " rated reviews", "muted"));
        }
        var categories = breakdown(data, "categories", row -> state(text(row, "topic")), "No tagged reviews.");
        var trend = breakdown(data, "trend", row -> value(row, "date"), "No dated reviews.");
        if (rows(data, "trend").size() > 4) {
            var scroll = new javafx.scene.control.ScrollPane(trend);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(128);
            charts.getChildren().setAll(metrics, paired(panel("Rating Distribution", content(distribution)),
                    panel("Feedback Categories", content(categories))), panel("Feedback Trend by Day", content(scroll)));
        } else {
            charts.getChildren().setAll(metrics, paired(panel("Rating Distribution", content(distribution)),
                    panel("Feedback Categories", content(categories))), panel("Feedback Trend by Day", content(trend)));
        }
    }

    private static Node breakdown(JsonObject data, String key, java.util.function.Function<JsonObject, String> name,
                                  String empty) {
        var group = new VBox(6);
        for (var row : rows(data, key)) {
            group.getChildren().add(label(name.apply(row) + "  ·  " + value(row, "count") + " reviews  ·  "
                    + average(row) + " average", "muted"));
        }
        return group.getChildren().isEmpty() ? label(data == null || !(data.get(key) instanceof JsonArray)
                ? UNAVAILABLE : empty, "muted") : group;
    }

    private void detail(JsonObject row) {
        if (row == null) {
            return;
        }
        var back = button("Back to reviews", () -> {
            view.getChildren().setAll(list);
            apply();
            top.run();
        });
        view.getChildren().setAll(back, panel("Review Details", content(
                fact("Review identifier", value(row, "id")),
                fact("Submitted", timestamp(row, "submittedAt")),
                fact("Rating", stars(row)),
                fact("Feedback categories", tags(row)),
                fact("Table / pickup", value(row, "serviceReference")),
                fact("Dining session", value(row, "sessionId")),
                fact("Customer comment", text(row, "comment").isBlank() ? "No comment supplied." : text(row, "comment")))),
                panel("Management Follow-up", followUpEditor(row)));
        top.run();
    }

    private VBox followUpEditor(JsonObject row) {
        long version = row.has("version") && !row.get("version").isJsonNull() ? row.get("version").getAsLong() : 0;
        if (version <= 0) {
            return content(fact("Status", state(text(row, "followUp"))),
                    fact("Internal response", text(row, "internalResponse")),
                    label("Follow-up editing is unavailable for this review source.", "muted"));
        }
        var states = new ComboBox<Choice>();
        for (String code : List.of("NEEDS_ATTENTION", "FOLLOWED_UP", "RESOLVED")) {
            var state = new Choice(OperationsPresentation.state(code), code);
            states.getItems().add(state);
            if (code.equals(text(row, "followUp"))) states.setValue(state);
        }
        var note = new TextArea(text(row, "internalResponse"));
        note.setPromptText("Manager-only note");
        note.setWrapText(true);
        note.setPrefRowCount(3);
        var error = label("", "muted");
        var form = content(field("Status", states), field("Internal response", note), error);
        var save = button("Save follow-up", () -> {
            var payload = new JsonObject();
            payload.addProperty("followUp", states.getValue().code());
            payload.addProperty("internalResponse", note.getText().strip());
            payload.addProperty("expectedVersion", version);
            form.setDisable(true);
            AdminWorkspace.background(() -> api.reviewFollowUp(java.util.UUID.fromString(text(row, "id")), payload),
                    (updated, failure) -> {
                        if (form.getScene() == null) return;
                        form.setDisable(false);
                        if (failure == null) {
                            row.add("followUp", updated.get("followUp"));
                            row.add("internalResponse", updated.get("internalResponse"));
                            row.add("version", updated.get("version"));
                            detail(row);
                        } else if (failure instanceof AdminApi.Rejected rejected && rejected.status == 409) {
                            form.setDisable(true);
                            error.setText("This follow-up changed. Go back to reload the latest review.");
                        } else {
                            error.setText(failureMessage(failure));
                        }
                    });
        });
        save.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() ->
                states.getValue() == null || note.getText().codePointCount(0, note.getText().length()) > 500,
                states.valueProperty(), note.textProperty()));
        form.getChildren().add(save);
        return form;
    }

    static String query(String search, LocalDate from, LocalDate to, String rating, String topic, String followUp) {
        if (search.strip().length() > 120 || from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid date range or search length.");
        }
        var values = new ArrayList<>(List.of("limit=25"));
        add(values, "q", search.strip());
        add(values, "from", from);
        add(values, "to", to);
        add(values, "rating", rating);
        add(values, "topic", topic);
        add(values, "followUp", followUp);
        return String.join("&", values);
    }

    private static void add(List<String> values, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            values.add(key + "=" + encode(value.toString()));
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static double fraction(JsonObject row, JsonObject data) {
        try {
            long total = Long.parseLong(text(data, "rated"));
            return total <= 0 ? 0 : Math.clamp(Long.parseLong(text(row, "count")) / (double) total, 0, 1);
        } catch (NumberFormatException missing) {
            return 0;
        }
    }

    private static String counts(JsonObject data, String... keys) {
        return data == null ? UNAVAILABLE : java.util.Arrays.stream(keys).map(key -> value(data, key))
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private static String average(JsonObject row) {
        String rating = text(row, "average");
        return rating.isBlank() ? UNAVAILABLE : rating + " / 5";
    }

    private static String stars(JsonObject row) {
        String rating = text(row, "rating");
        return rating.isBlank() ? UNAVAILABLE : rating + " / 5";
    }

    private static String tags(JsonObject row) {
        var tags = strings(row, "topics");
        return tags.isEmpty() ? "None supplied" : tags.stream().map(OperationsPresentation::state)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static VBox fact(String title, String value) {
        return new VBox(3, label(title, "field-label"), label(value, "muted"));
    }

    private static VBox metric(String title, String value) {
        var card = new VBox(5, label(title, "muted"), label(value, "catalog-emphasis"));
        card.setMinWidth(132);
        card.getStyleClass().add("catalog-stat");
        return card;
    }

    private static DatePicker datePicker() {
        var picker = new DatePicker();
        picker.setPromptText("Any date");
        picker.setPrefWidth(133);
        return picker;
    }

    private static LocalDate date(DatePicker picker) {
        String entered = picker.getEditor().getText();
        return entered.isBlank() ? null : picker.getConverter().fromString(entered);
    }

    private static ComboBox<Choice> filter(String all, String... choices) {
        var combo = new ComboBox<Choice>();
        combo.getItems().add(new Choice(all, ""));
        for (String choice : choices) {
            combo.getItems().add(new Choice(state(choice), choice));
        }
        combo.getSelectionModel().selectFirst();
        return combo;
    }

    private record Choice(String label, String code) {
        @Override public String toString() { return label; }
    }
}
