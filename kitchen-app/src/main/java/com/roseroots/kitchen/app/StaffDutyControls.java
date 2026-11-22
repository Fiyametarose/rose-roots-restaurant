package com.roseroots.kitchen.app;

import com.google.gson.JsonObject;
import com.roseroots.kitchen.service.http.SpringBootKitchenService;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

final class StaffDutyControls extends HBox {
    private final SpringBootKitchenService service;
    private final Label status = new Label();
    private final ComboBox<Integer> hours = new ComboBox<>();
    private final Button change = new Button("Start Shift");
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(3), event -> refresh()));
    private SpringBootKitchenService.StaffSession session;
    private JsonObject state;
    private boolean busy;

    StaffDutyControls(SpringBootKitchenService service) {
        super(6);
        this.service = service;
        hours.getItems().setAll(4, 8, 12);
        hours.setValue(8);
        hours.setAccessibleText("Shift duration in hours");
        change.setOnAction(event -> update());
        getChildren().setAll(status, hours, change);
        poll.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, previous, current) -> {
            if (current == null) poll.stop();
            else poll.play();
        });
    }

    void reflect(SpringBootKitchenService.StaffSession staff) {
        if (staff != null && !staff.role().equals("CHEF") && !staff.role().equals("WAITER")) staff = null;
        if (session != staff) {
            session = staff;
            state = null;
            busy = false;
            render();
            refresh();
        }
        setVisible(staff != null);
        setManaged(staff != null);
    }

    private void refresh() {
        if (session == null || busy) return;
        var actor = session;
        busy = true;
        service.staffDuty().whenComplete((data, failure) -> Platform.runLater(() -> received(actor, data, failure)));
    }

    private void update() {
        if (busy || state == null) return;
        var actor = session;
        busy = true;
        change.setDisable(true);
        service.changeDuty(state.get("onDuty").getAsBoolean() ? 0 : hours.getValue(), state.get("version").getAsLong())
                .whenComplete((data, failure) -> Platform.runLater(() -> received(actor, data, failure)));
    }

    private void received(SpringBootKitchenService.StaffSession actor, JsonObject data, Throwable failure) {
        if (session != actor) return;
        busy = false;
        state = failure == null ? data : null;
        service.displayDuty(actor, state != null && state.get("onDuty").getAsBoolean());
        render();
    }

    private void render() {
        change.setDisable(state == null);
        hours.setDisable(state == null);
        if (state == null) {
            status.setText("Checking duty status");
            return;
        }
        boolean on = state.get("onDuty").getAsBoolean();
        var assignment = state.getAsJsonArray(session.role().equals("CHEF") ? "stations" : "tables");
        String area = assignment.asList().stream().map(com.google.gson.JsonElement::getAsString)
                .collect(Collectors.joining(", "));
        if (area.isEmpty()) area = session.role().equals("CHEF") ? "No station assigned" : "Restaurant-wide";
        else if (session.role().equals("WAITER")) area = "Tables " + area;
        status.setText(area + "\n" + (on ? "On Duty" : "Off Duty") + time("shiftStartedAt", "Started")
                + time("availableUntil", on ? "Ends" : "Last end"));
        change.setText(on ? "End Shift" : "Start Shift");
        hours.setDisable(on);
    }

    private String time(String field, String label) {
        if (!state.has(field) || state.get(field).isJsonNull()) return "";
        var time = OffsetDateTime.parse(state.get(field).getAsString()).atZoneSameInstant(ZoneId.systemDefault());
        return " | " + label + " " + DateTimeFormatter.ofPattern("MMM d HH:mm").format(time);
    }
}
