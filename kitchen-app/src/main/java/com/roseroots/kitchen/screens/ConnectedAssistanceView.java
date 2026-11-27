package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.components.StaffDialogs;

import com.roseroots.kitchen.service.http.ConnectedAssistanceRequest;
import com.roseroots.kitchen.service.http.ConnectedAssistanceRequest.State;
import com.roseroots.kitchen.service.http.SpringBootKitchenService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class ConnectedAssistanceView extends VBox {
    private final SpringBootKitchenService rest;
    private final IntConsumer activeCount;
    private final PauseTransition next = new PauseTransition();
    private final Label status = label("Loading assistance requests…", "connection-state");
    private final VBox rows = new VBox(8);
    private List<ConnectedAssistanceRequest> snapshot = List.of();
    private ConnectedAssistanceRequest queued;
    private boolean fetching;
    private boolean stopped;
    private int failures;
    private SpringBootKitchenService.StaffSession shownStaff;
    private boolean shownDuty;
    private record Waiter(UUID id, String username) {
        @Override public String toString() { return username; }
    }

    private void chooseWaiter(ConnectedAssistanceRequest row, SpringBootKitchenService.StaffSession actor) {
        rest.eligibleWaiters(row).whenComplete((values, failure) -> Platform.runLater(() -> {
            if (stopped || rest.signedInStaff() != actor || getScene() == null) return;
            if (failure != null || values.isEmpty()) {
                status.setText("No eligible on-duty Waiter could be loaded.");
                return;
            }
            var dialog = new javafx.scene.control.Dialog<javafx.scene.control.ButtonType>();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle("Reassign assistance");
            var waiters = new javafx.scene.control.ComboBox<Waiter>();
            values.forEach(value -> {
                var record = value.getAsJsonObject();
                waiters.getItems().add(new Waiter(UUID.fromString(record.get("id").getAsString()), record.get("username").getAsString()));
            });
            waiters.getSelectionModel().selectFirst();
            var reason = new javafx.scene.control.TextField();
            reason.setPromptText("Reassignment reason");
            dialog.getDialogPane().setContent(new VBox(8, new Label("On-duty Waiter"), waiters, reason));
            var ok = javafx.scene.control.ButtonType.OK;
            dialog.getDialogPane().getButtonTypes().setAll(ok, javafx.scene.control.ButtonType.CANCEL);
            dialog.getDialogPane().lookupButton(ok).disableProperty().bind(reason.textProperty().isEmpty());
            StaffDialogs.theme(dialog);
            if (dialog.showAndWait().orElse(null) == ok && rest.signedInStaff() == actor) {
                rest.reassignAssistance(row, waiters.getValue().id(), reason.getText().strip())
                        .whenComplete((ignored, error) -> Platform.runLater(() -> {
                            if (stopped) return;
                            status.setText(error == null ? "Assistance reassigned." : "Could not reassign. Check the latest status.");
                            if (!fetching) { next.stop(); read(); }
                        }));
            }
        }));
    }

    public ConnectedAssistanceView(SpringBootKitchenService rest, IntConsumer activeCount) {
        this.rest = rest;
        this.activeCount = activeCount;
        setSpacing(8);
        rows.getStyleClass().add("ticket-list");
        var scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(status, scroll);
        next.setOnFinished(event -> read());
        read();
    }

    public void stop() {
        stopped = true;
        next.stop();
        queued = null;
    }

    private void read() {
        if (stopped) return;
        fetching = true;
        var actor = rest.signedInStaff();
        rest.assistanceRequests().whenComplete((requests, failure) ->
                Platform.runLater(() -> {
                    if (stopped) return;
                    if (actor != rest.signedInStaff()) {
                        queued = null;
                        snapshot = List.of();
                        render();
                        read();
                        return;
                    }
                    received(requests, failure);
                }));
    }

    private void received(List<ConnectedAssistanceRequest> requests, Throwable failure) {
        if (stopped) return;
        fetching = false;
        var action = queued;
        queued = null;
        Map<UUID, Long> versions = snapshot.stream().collect(Collectors.toMap(
                ConnectedAssistanceRequest::id, ConnectedAssistanceRequest::version));
        if (failure != null || requests.stream().anyMatch(row -> row.version() < versions.getOrDefault(row.id(), 0L))) {
            failures = Math.min(failures + 1, 4);
            snapshot = List.of();
            status.setText(action == null ? "Assistance could not synchronize. Retrying."
                    : "The queued action was not sent. Assistance is reconnecting.");
            render();
        } else {
            boolean changed = failures > 0 || !snapshot.equals(requests) || rows.getChildren().isEmpty()
                    || shownStaff != rest.signedInStaff() || shownDuty != rest.waiterOnDuty();
            failures = 0;
            snapshot = requests;
            if (action != null) {
                if (snapshot.contains(action)) {
                    write(action);
                    return;
                }
                status.setText("This request changed before the action was sent. Review the latest status.");
                changed = true;
            } else if (status.getText().startsWith("Loading") || status.getText().contains("reconnect")
                    || status.getText().contains("Retrying")) {
                status.setText("Assistance synchronizes automatically.");
            }
            if (changed) render();
        }
        next.setDuration(Duration.seconds(Math.min(30, 3L << failures)));
        next.playFromStart();
    }

    private void advance(ConnectedAssistanceRequest request) {
        if (fetching) {
            if (queued == null) {
                queued = request;
                status.setText("Checking the current request version…");
                render();
            }
        } else {
            write(request);
        }
    }

    private void write(ConnectedAssistanceRequest request) {
        next.stop();
        rows.setDisable(true);
        status.setText("Saving assistance status…");
        var actor = rest.signedInStaff();
        rest.advanceAssistance(request).whenComplete((updated, failure) -> Platform.runLater(() -> {
            if (stopped) return;
            if (actor != rest.signedInStaff()) {
                snapshot = List.of();
                rows.setDisable(false);
                render();
                read();
                return;
            }
            if (failure == null) {
                snapshot = snapshot.stream().map(row -> row.id().equals(updated.id()) ? updated : row).toList();
                status.setText("Assistance status saved.");
            } else {
                snapshot = List.of();
                status.setText("The change could not be confirmed. Checking the latest requests.");
            }
            rows.setDisable(false);
            render();
            read();
        }));
    }

    private void render() {
        rows.getChildren().clear();
        shownStaff = rest.signedInStaff();
        shownDuty = rest.waiterOnDuty();
        activeCount.accept((int) snapshot.stream().filter(row -> row.status() != State.COMPLETED).count());
        snapshot.stream().sorted(Comparator.comparing((ConnectedAssistanceRequest row) -> row.status() == State.COMPLETED)
                .thenComparing(ConnectedAssistanceRequest::updatedAt, Comparator.reverseOrder()))
                .forEach(request -> {
                    var header = new HBox(8, label(request.destination(), "destination"),
                            label(request.type().label, "item-name"), label(request.status().label, "normal"));
                    var card = new VBox(5, header, label(request.id().toString(), "metadata"),
                            label(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                                    .format(request.updatedAt()), "metadata"));
                    card.getStyleClass().addAll("ticket", "assistance-card");
                    if (!request.note().isBlank()) card.getChildren().add(label(request.note(), "customer-note"));
                    if (request.status() != State.COMPLETED && shownStaff != null
                            && shownDuty
                            && (shownStaff.id().equals(request.ownerStaffId())
                               || request.ownerStaffId() == null && request.status() == State.SUBMITTED)) {
                        var action = new Button(request.status().action);
                        action.setId("assistance-action-" + request.id());
                        action.setDisable(queued != null);
                        action.setOnAction(event -> advance(request));
                        card.getChildren().add(action);
                    }
                    if (request.status() != State.COMPLETED && shownStaff != null && "SUPERVISOR".equals(shownStaff.role())) {
                        var reassign = new Button("Reassign");
                        var actor = shownStaff;
                        reassign.setOnAction(event -> chooseWaiter(request, actor));
                        card.getChildren().add(reassign);
                    }
                    if (request.status() != State.COMPLETED && request.ownerStaffId() != null) {
                        card.getChildren().add(label(shownStaff != null && shownStaff.id().equals(request.ownerStaffId())
                                ? "Assigned to you" : "Assigned to a Waiter", "metadata"));
                    }
                    rows.getChildren().add(card);
                });
        if (snapshot.isEmpty() && failures == 0) rows.getChildren().add(label("No assistance requests.", "empty"));
    }

    private static Label label(String text, String style) {
        var label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }
}
