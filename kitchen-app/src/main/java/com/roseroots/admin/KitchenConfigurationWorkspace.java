package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
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

final class KitchenConfigurationWorkspace extends VBox {
    private record Choice(String id, String label, JsonObject data) {
        @Override public String toString() { return label; }
    }

    private record Data(List<Choice> stations, List<Choice> chefs, List<Choice> menu, List<JsonObject> profiles) {}

    private final AdminApi api;
    private final Runnable expired;
    private final Button refresh = new Button("Refresh configuration");
    private final Label status = new Label();
    private final VBox content = new VBox(14);

    KitchenConfigurationWorkspace(AdminApi api, Runnable expired) {
        super(12);
        this.api = api;
        this.expired = expired;
        var operational = new VBox(10, refresh, status, content);
        var tabs = new TabPane(new Tab("Stations and capacity", operational),
                new Tab("Order priority", new ManagerPriorityWorkspace(api, expired)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getChildren().add(tabs);
        refresh.setOnAction(event -> load());
        load();
    }

    private void load() {
        run(() -> new Data(choices(api.kitchenConfiguration("stations"), "name"),
                choices(api.kitchenConfiguration("chef-availability"), "username"),
                choices(api.catalog().getAsJsonArray("items"), "name"),
                objects(api.kitchenConfiguration("prep-times"))), data -> {
            var stationRows = new VBox(8);
            data.stations().forEach(station -> stationRows.getChildren().add(station(station, data.chefs())));
            var chefRows = new VBox(8);
            data.chefs().forEach(chef -> chefRows.getChildren().add(chef(chef)));
            if (data.chefs().isEmpty()) chefRows.getChildren().add(new Label("Create Chef accounts in Staff & Security."));
            content.getChildren().setAll(section("Stations", stationRows), section("Chef availability", chefRows),
                    section("Menu preparation profiles", profile(data)));
        });
    }

    private Node station(Choice station, List<Choice> chefs) {
        var value = station.data();
        var enabled = new CheckBox("Station active");
        enabled.setSelected(value.get("active").getAsBoolean());
        var slots = new TextField(value.get("parallelSlots").getAsString());
        slots.setAccessibleText(station.label() + " parallel slots");
        slots.setPrefColumnCount(4);
        var assignments = new FlowPane(8, 5);
        var current = value.getAsJsonArray("chefIds").asList().stream().map(entry -> entry.getAsString()).toList();
        List<CheckBox> choices = chefs.stream().map(chef -> {
            var check = new CheckBox(chef.label());
            check.setSelected(current.contains(chef.id()));
            check.setUserData(chef.id());
            return check;
        }).toList();
        assignments.getChildren().addAll(choices);
        var save = new Button("Save station");
        save.setOnAction(event -> {
            Integer capacity = number(slots, 0, 1000, "parallel slots");
            if (capacity == null) return;
            var body = version(value);
            body.addProperty("active", enabled.isSelected());
            body.addProperty("parallelSlots", capacity);
            var ids = new JsonArray();
            choices.stream().filter(CheckBox::isSelected).forEach(choice -> ids.add((String) choice.getUserData()));
            body.add("chefIds", ids);
            save("stations/" + station.id(), body);
        });
        return new VBox(5, new FlowPane(8, 4, new Label(station.label()), enabled, new Label("Parallel slots"), slots,
                new Label(value.get("qualifiedAvailableChefs").getAsInt() + " assigned and available"), save),
                new Label("Qualified Chef assignments"), assignments);
    }

    private Node chef(Choice chef) {
        var value = chef.data();
        var minutes = new TextField();
        minutes.setPromptText("Minutes, 0 for off duty");
        var until = value.get("availableUntil");
        String availability = !value.get("enabled").getAsBoolean() ? "Disabled" : until == null || until.isJsonNull()
                || !java.time.OffsetDateTime.parse(until.getAsString()).isAfter(java.time.OffsetDateTime.now())
                ? "Off duty" : "Available until " + java.time.OffsetDateTime.parse(until.getAsString())
                        .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0);
        var save = new Button("Set availability");
        save.setDisable(!value.get("enabled").getAsBoolean());
        save.setOnAction(event -> {
            Integer amount = number(minutes, 0, 720, "minutes");
            if (amount == null) return;
            var body = version(value);
            body.addProperty("minutes", amount);
            save("chef-availability/" + chef.id(), body);
        });
        return new FlowPane(8, 5, new Label(chef.label()), new Label(availability), minutes, save);
    }

    private Node profile(Data data) {
        var dish = new ComboBox<Choice>();
        dish.setPromptText("Menu item");
        dish.getItems().setAll(data.menu());
        var station = new ComboBox<Choice>();
        station.setPromptText("Preparation station");
        station.getItems().setAll(data.stations());
        var seconds = new TextField();
        seconds.setPromptText("Duration in seconds");
        var save = new Button("Save profile");
        save.disableProperty().bind(dish.valueProperty().isNull().or(station.valueProperty().isNull()));
        dish.setOnAction(event -> {
            var existing = current(data, dish.getValue());
            seconds.setText(existing == null ? "" : existing.get("durationSeconds").getAsString());
            station.setValue(existing == null ? null : data.stations().stream()
                    .filter(choice -> choice.id().equals(existing.get("stationId").getAsString())).findFirst().orElse(null));
        });
        save.setOnAction(event -> {
            Integer duration = number(seconds, 1, 86400, "seconds");
            if (duration == null) return;
            var existing = current(data, dish.getValue());
            var body = existing == null ? new JsonObject() : version(existing);
            if (existing == null) body.addProperty("expectedVersion", 0);
            body.addProperty("stationId", station.getValue().id());
            body.addProperty("durationSeconds", duration);
            save("prep-times/" + dish.getValue().id(), body);
        });
        return new FlowPane(8, 5, dish, station, seconds, save);
    }

    private static JsonObject current(Data data, Choice dish) {
        return dish == null ? null : data.profiles().stream()
                .filter(profile -> dish.id().equals(profile.get("menuItemId").getAsString())).findFirst().orElse(null);
    }

    private void save(String endpoint, JsonObject body) {
        run(() -> { api.writeCatalog("PATCH", endpoint, body); return null; }, ignored -> load());
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        content.setDisable(true);
        refresh.setDisable(true);
        status.setText("Contacting kitchen configuration…");
        AdminWorkspace.background(task, (result, error) -> {
            content.setDisable(false);
            refresh.setDisable(false);
            if (error == null) {
                status.setText("");
                done.accept(result);
            } else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                expired.run();
            } else {
                status.setText("The configuration request failed. Check the values and refresh before retrying.");
            }
        });
    }

    private Integer number(TextField field, int min, int max, String unit) {
        try {
            int number = Integer.parseInt(field.getText().strip());
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException ignored) {
            // Show the same range for incomplete and invalid input.
        }
        status.setText("Enter " + min + "–" + max + " " + unit + ".");
        return null;
    }

    private static List<Choice> choices(JsonArray array, String label) {
        return objects(array).stream().map(value -> new Choice(value.get("id").getAsString(),
                value.get(label).getAsString(), value)).toList();
    }

    private static List<JsonObject> objects(JsonArray array) {
        return array.asList().stream().map(value -> value.getAsJsonObject()).toList();
    }

    private static JsonObject version(JsonObject record) {
        var result = new JsonObject();
        result.addProperty("expectedVersion", record.get("version").getAsLong());
        return result;
    }

    private static VBox section(String title, Node content) {
        var heading = new Label(title);
        heading.getStyleClass().add("section-title");
        return new VBox(8, heading, content);
    }
}
