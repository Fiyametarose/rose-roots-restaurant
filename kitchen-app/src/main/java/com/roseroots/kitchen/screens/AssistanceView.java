package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.model.AssistanceRequest;
import com.roseroots.kitchen.model.AssistanceRequest.Action;
import com.roseroots.kitchen.model.AssistanceRequest.Status;
import com.roseroots.kitchen.service.AssistanceService;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class AssistanceView extends VBox {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d · h:mm:ss a", Locale.ENGLISH);
    private final AssistanceService service;
    private final Label total = label("", "priority-stat-count");
    private final Label feedback = label("", "customer-note");
    private final VBox active = new VBox(7);
    private final VBox resolved = new VBox(7);
    private final Map<Status, Label> counts = new EnumMap<>(Status.class);
    private List<AssistanceRequest> snapshot;

    public AssistanceView(AssistanceService service) {
        this.service = service;
        setSpacing(10);
        HBox metrics = new HBox(8, metric("Total requests", total));
        for (Status status : Status.values()) {
            Label count = label("", "priority-stat-count");
            counts.put(status, count);
            metrics.getChildren().add(metric(status.label, count));
        }
        VBox current = panel("Requests needing attention", active);
        VBox completed = panel("Resolved requests", resolved);
        current.setPrefWidth(600);
        completed.setPrefWidth(350);
        HBox sections = new HBox(10, current, completed);
        VBox.setVgrow(sections, Priority.ALWAYS);
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
        feedback.visibleProperty().bind(feedback.managedProperty());
        getChildren().addAll(metrics, feedback, sections);
    }

    public void refresh() {
        List<AssistanceRequest> requests = service.requests();
        if (requests.equals(snapshot)) {
            return;
        }
        snapshot = requests;
        active.getChildren().clear();
        resolved.getChildren().clear();
        total.setText(Integer.toString(requests.size()));
        counts.forEach((status, label) -> label.setText(Long.toString(
                requests.stream().filter(request -> request.status() == status).count())));
        requests.stream().sorted(Comparator.comparing((AssistanceRequest request) ->
                        request.createdAt().toInstant()).reversed().thenComparingLong(AssistanceRequest::id))
                .forEach(request -> {
                    VBox destination = request.status() == Status.RESOLVED ? resolved : active;
                    destination.getChildren().add(card(request));
                });
        if (active.getChildren().isEmpty()) {
            active.getChildren().add(label("No open requests", "empty"));
        }
        if (resolved.getChildren().isEmpty()) {
            resolved.getChildren().add(label("No resolved requests", "empty"));
        }
    }

    private VBox card(AssistanceRequest request) {
        Status status = request.status();
        String badgeStyle = switch (status) {
            case PENDING -> "delayed";
            case IN_PROGRESS -> "elapsed";
            case RESOLVED -> "normal";
        };
        Label title = label(request.type().label + " · " + request.reference(), "destination");
        FlowPane heading = new FlowPane(6, 4, title, label(status.label, badgeStyle));
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(5, heading,
                label("AS-" + request.id() + " · Received " + TIME.format(request.createdAt()), "metadata"));
        card.getStyleClass().addAll("ticket", "assistance-card");
        card.setId("assistance-" + request.id());
        if (request.acknowledged() != null) {
            card.getChildren().add(actionLabel("Acknowledged", request.acknowledged()));
        }
        if (request.resolved() != null) {
            card.getChildren().add(actionLabel("Resolved", request.resolved()));
        }
        if (status != Status.RESOLVED) {
            Button action = new Button(status == Status.PENDING ? "Acknowledge" : "Resolve");
            action.getStyleClass().add(status == Status.PENDING ? "start" : "mark-ready");
            action.setAccessibleText(action.getText() + " request AS-" + request.id());
            action.setOnAction(event -> {
                try {
                    if (status == Status.PENDING) {
                        service.acknowledge(request.id());
                    } else {
                        service.resolve(request.id());
                    }
                    feedback.setText("");
                } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                    feedback.setText(ex.getMessage());
                }
                refresh();
            });
            card.getChildren().add(action);
        }
        return card;
    }

    private static Label actionLabel(String title, Action action) {
        return label(title + " " + TIME.format(action.at()) + " · " + action.staffId(), "item-note");
    }

    private static VBox panel(String name, VBox list) {
        Label header = label(name, "lane-title");
        VBox heading = new VBox(header);
        heading.getStyleClass().add("lane-heading");
        list.getStyleClass().add("ticket-list");
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox panel = new VBox(heading, scroll);
        panel.setMinWidth(0);
        panel.getStyleClass().add("lane");
        HBox.setHgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private static VBox metric(String name, Label count) {
        VBox metric = new VBox(2, count, label(name, "metadata"));
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
