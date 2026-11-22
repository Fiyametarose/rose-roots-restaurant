package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

final class KitchenConfigurationWorkspace extends VBox {
    private static final DateTimeFormatter SHIFT_END = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm z")
            .withZone(ZoneId.systemDefault());

    private record Choice(String id, String label, JsonObject data) {}
    private record Data(List<Choice> stations, List<Choice> chefs) {}
    private enum ShiftLength {
        FOUR(4), EIGHT(8), TWELVE(12);
        final int hours;
        ShiftLength(int hours) { this.hours = hours; }
        @Override public String toString() { return hours + " hours"; }
    }

    private final AdminApi api;
    private final Runnable expired;
    private final Button refresh = new Button("Refresh configuration");
    private final Label status = new Label();
    private final VBox stations = new VBox(14);
    private final VBox shifts = new VBox(12);
    private final List<Runnable> clockUpdates = new ArrayList<>();
    private final Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
    private Instant serverTime = Instant.now();
    private long sampledAt = System.nanoTime();

    KitchenConfigurationWorkspace(AdminApi api, Runnable expired) {
        super(12);
        this.api = api;
        this.expired = expired;
        status.setWrapText(true);
        status.setAccessibleText("Kitchen configuration status");
        var tabs = new TabPane(new Tab("Stations & assignments", stations),
                new Tab("Chef shifts", shifts), new Tab("Order priority", new ManagerPriorityWorkspace(api, expired)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getChildren().addAll(new FlowPane(8, 5, refresh, status), tabs);
        refresh.setOnAction(event -> load("Configuration refreshed.", null));
        clock.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((changed, old, current) -> {
            if (current == null) clock.stop();
            else clock.play();
        });
        load("", null);
    }

    private void tick() { clockUpdates.forEach(Runnable::run); }

    private Instant now() { return serverTime.plusNanos(System.nanoTime() - sampledAt); }

    private void load(String success, String focusId) {
        run(() -> new Data(choices(api.adminRows("stations"), "name"), choices(api.adminRows("chef-availability"), "username")),
                data -> {
                    sampledAt = System.nanoTime();
                    serverTime = data.chefs().isEmpty() ? Instant.now()
                            : parseTime(data.chefs().getFirst().data(), "serverTime");
                    clockUpdates.clear();
                    stations.getChildren().setAll(heading("Stations and capacity"));
                    data.stations().forEach(station -> stations.getChildren().add(station(station, data.chefs())));
                    shifts.getChildren().setAll(heading("Chef shifts and availability"));
                    data.chefs().forEach(chef -> shifts.getChildren().add(chef(chef)));
                    if (data.chefs().isEmpty()) shifts.getChildren().add(new Label("Create Chef accounts in Staff & Security."));
                    tick();
                    status.setText(success);
                    if (focusId != null) {
                        var target = lookup("#" + focusId);
                        if (target != null) target.requestFocus();
                    }
                });
    }

    private Node station(Choice station, List<Choice> chefs) {
        var value = station.data();
        boolean active = value.get("active").getAsBoolean();
        var enabled = new CheckBox("Station active");
        enabled.setSelected(active);
        var slots = new TextField(value.get("parallelSlots").getAsString());
        slots.setPrefColumnCount(4);
        var slotsLabel = new Label("Parallel slots");
        slotsLabel.setLabelFor(slots);
        var assignedIds = value.getAsJsonArray("chefIds").asList().stream().map(entry -> entry.getAsString()).toList();
        var saved = new Label();
        saved.setWrapText(true);
        clockUpdates.add(() -> {
            long onDuty = active ? chefs.stream().filter(chef -> assignedIds.contains(chef.id()) && onDuty(chef)).count() : 0;
            saved.setText((active ? "Active" : "Inactive") + " · " + value.get("parallelSlots").getAsInt()
                    + " parallel slots · " + onDuty + " assigned Chef(s) on duty");
        });
        var assignments = new FlowPane(12, 8);
        List<CheckBox> choices = chefs.stream().filter(chef -> chef.data().get("enabled").getAsBoolean() || assignedIds.contains(chef.id()))
                .map(chef -> {
                    var check = new CheckBox(chef.label());
                    check.setSelected(assignedIds.contains(chef.id()));
                    check.setUserData(chef.id());
                    clockUpdates.add(() -> check.setText(chef.label() + " (" + dutyLabel(chef) + ")"));
                    return check;
                }).toList();
        assignments.getChildren().addAll(choices);
        if (choices.isEmpty()) assignments.getChildren().add(new Label("No Chef accounts are available to assign."));
        var save = new Button("Save station");
        save.setAccessibleText("Save " + station.label() + " capacity and Chef assignments");
        save.setOnAction(event -> {
            Integer capacity = number(slots);
            if (capacity == null) return;
            var body = version(value);
            body.addProperty("active", enabled.isSelected());
            body.addProperty("parallelSlots", capacity);
            var ids = new JsonArray();
            choices.stream().filter(CheckBox::isSelected).forEach(choice -> ids.add((String) choice.getUserData()));
            body.add("chefIds", ids);
            save("stations/" + station.id(), body, station.label() + " saved.", null);
        });
        return new VBox(8, heading(station.label()), saved,
                new FlowPane(12, 8, enabled, slotsLabel, slots), new Label("Chef assignments"), assignments, save);
    }

    private Node chef(Choice chef) {
        var value = chef.data();
        var state = new Label();
        var coverage = new Label();
        coverage.setWrapText(true);
        var length = new ComboBox<ShiftLength>();
        length.getItems().setAll(ShiftLength.values());
        length.setValue(ShiftLength.EIGHT);
        var lengthLabel = new Label("Shift length");
        lengthLabel.setLabelFor(length);
        var action = new Button();
        String focusId = "chef-shift-" + chef.id();
        action.setId(focusId);
        boolean eligible = value.get("eligible").getAsBoolean();
        long unfinished = value.get("unfinishedItems").getAsLong();
        clockUpdates.add(() -> {
            boolean on = onDuty(chef);
            String label = dutyLabel(chef);
            if (on) label += " · Ends " + SHIFT_END.format(parseTime(value, "availableUntil"));
            state.setText(label);
            action.setText(on ? "End Shift" : "Start Shift");
            action.setUserData(on);
            action.setAccessibleText(action.getText() + " for " + chef.label());
            action.setDisable(!eligible);
            length.setDisable(!eligible || on);
            coverage.setText(unfinished == 0 ? "" : unfinished + " unfinished item(s) assigned."
                    + (on ? "" : " A Supervisor needs to reassign this work."));
        });
        coverage.visibleProperty().bind(coverage.textProperty().isNotEmpty());
        coverage.managedProperty().bind(coverage.visibleProperty());
        action.setOnAction(event -> {
            boolean ending = Boolean.TRUE.equals(action.getUserData());
            var body = version(value);
            body.addProperty("minutes", ending ? 0 : length.getValue().hours * 60);
            save("chef-availability/" + chef.id(), body,
                    chef.label() + (ending ? " is off duty." : " is on duty."), focusId);
        });
        return new VBox(7, heading(chef.label()), state, coverage, new FlowPane(12, 8, lengthLabel, length, action));
    }

    private boolean onDuty(Choice chef) {
        var until = parseTime(chef.data(), "availableUntil");
        return chef.data().get("eligible").getAsBoolean() && until != null && until.isAfter(now());
    }

    private String dutyLabel(Choice chef) {
        var data = chef.data();
        if (data.get("archived").getAsBoolean()) return "Archived";
        if (!data.get("enabled").getAsBoolean()) return "Deactivated";
        if (!data.get("eligible").getAsBoolean()) return "Access unavailable";
        return onDuty(chef) ? "On Duty" : "Off Duty";
    }

    private static Instant parseTime(JsonObject data, String field) {
        var element = data.get(field);
        return element == null || element.isJsonNull() ? null : OffsetDateTime.parse(element.getAsString()).toInstant();
    }

    private void save(String endpoint, JsonObject body, String success, String focusId) {
        run(() -> { api.writeCatalog("PATCH", endpoint, body); return null; }, ignored -> load(success, focusId));
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        stations.setDisable(true);
        shifts.setDisable(true);
        refresh.setDisable(true);
        status.setText("Contacting kitchen configuration…");
        AdminWorkspace.background(task, (result, error) -> {
            stations.setDisable(false);
            shifts.setDisable(false);
            refresh.setDisable(false);
            if (error == null) {
                done.accept(result);
            } else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                expired.run();
            } else if (error instanceof AdminApi.Rejected rejected && rejected.status == 409) {
                status.setText("The station or Chef changed or is unavailable. Refresh configuration before retrying.");
            } else {
                status.setText("The configuration request failed. Check the values and try again.");
            }
        });
    }

    private Integer number(TextField field) {
        try {
            int number = Integer.parseInt(field.getText().strip());
            if (number >= 0 && number <= 1000) return number;
        } catch (NumberFormatException ignored) {
            // Display the same range for incomplete and invalid input.
        }
        status.setText("Enter a whole number from 0 to 1000 for parallel slots.");
        field.requestFocus();
        return null;
    }

    private static List<Choice> choices(JsonArray array, String label) {
        return array.asList().stream().map(value -> value.getAsJsonObject()).map(value -> new Choice(
                value.get("id").getAsString(), value.get(label).getAsString(), value)).toList();
    }

    private static JsonObject version(JsonObject record) {
        var result = new JsonObject();
        result.addProperty("expectedVersion", record.get("version").getAsLong());
        return result;
    }

    private static Node heading(String title) {
        var label = new Label(title);
        label.getStyleClass().add("section-title");
        return label;
    }
}
