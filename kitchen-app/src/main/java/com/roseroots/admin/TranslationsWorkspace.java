package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class TranslationsWorkspace extends VBox {
    private record Entry(String type, String id, String name, String description) {
        @Override public String toString() { return (type.equals("DISH") ? "Dish · " : "Category · ") + name; }
    }
    private record Language(String code, String name) {
        @Override public String toString() { return name; }
    }
    private record Data(List<Entry> entries, List<JsonObject> translations) {}

    private final AdminApi api;
    private final Runnable expired;
    private final ComboBox<Entry> entry = new ComboBox<>();
    private final ComboBox<Language> language = new ComboBox<>();
    private final Label source = new Label();
    private final Label status = new Label();
    private final TextField name = new TextField();
    private final TextArea description = new TextArea();
    private final Button save = new Button("Save translation");
    private final Button refresh = new Button("Refresh translations");
    private final VBox form;
    private List<JsonObject> records = List.of();

    TranslationsWorkspace(AdminApi api, Runnable expired) {
        super(12);
        this.api = api;
        this.expired = expired;
        var heading = new Label("Translations");
        heading.getStyleClass().add("heading");
        entry.setPromptText("Catalog entry");
        language.getItems().setAll(new Language("am", "Amharic"), new Language("ar", "Arabic"));
        language.getSelectionModel().selectFirst();
        entry.setOnAction(event -> fill());
        language.setOnAction(event -> fill());
        name.setPromptText("Translated name");
        description.setPromptText("Translated description");
        description.setWrapText(true);
        description.setPrefRowCount(4);
        source.setWrapText(true);
        save.disableProperty().bind(entry.valueProperty().isNull());
        save.setOnAction(event -> save());
        refresh.setOnAction(event -> load());
        form = new VBox(8, new FlowPane(8,5,entry,language), source, name, description, save);
        getChildren().addAll(heading, new Label("Blank translations use the catalog's base text."), refresh, status, form);
        load();
    }

    private void load() {
        run(() -> {
            var catalog = api.catalog();
            var entries = new ArrayList<Entry>();
            catalog.getAsJsonArray("categories").forEach(value -> {
                var row = value.getAsJsonObject();
                entries.add(new Entry("CATEGORY", value(row,"id"), value(row,"name"), ""));
            });
            catalog.getAsJsonArray("items").forEach(value -> {
                var row = value.getAsJsonObject();
                entries.add(new Entry("DISH", value(row,"id"), value(row,"name"), value(row,"description")));
            });
            return new Data(entries, api.adminRows("translations").asList().stream().map(row -> row.getAsJsonObject()).toList());
        }, data -> {
            var selected = entry.getValue();
            records = data.translations();
            entry.getItems().setAll(data.entries());
            if (selected != null) entry.setValue(data.entries().stream().filter(row -> row.id().equals(selected.id())
                    && row.type().equals(selected.type())).findFirst().orElse(null));
            fill();
        });
    }

    private void fill() {
        var selected = entry.getValue();
        var current = current();
        source.setText(selected == null ? "" : selected.name() + (selected.description().isBlank() ? "" : "\n" + selected.description()));
        name.setText(current == null ? "" : value(current,"name"));
        description.setText(current == null ? "" : value(current,"description"));
        description.setDisable(selected == null || !selected.type().equals("DISH"));
    }

    private void save() {
        var selected = entry.getValue();
        var previous = current();
        var body = new JsonObject();
        body.addProperty("targetType", selected.type());
        body.addProperty("targetId", selected.id());
        body.addProperty("locale", language.getValue().code());
        body.addProperty("name", name.getText());
        body.addProperty("description", selected.type().equals("DISH") ? description.getText() : "");
        body.addProperty("expectedVersion", previous == null ? 0 : previous.get("version").getAsLong());
        run(() -> api.adminWrite("translations", "PATCH", body, 200), saved -> load());
    }

    private JsonObject current() {
        var selected = entry.getValue();
        return selected == null ? null : records.stream().filter(row -> selected.type().equals(value(row,"targetType"))
                && selected.id().equals(value(row,"targetId")) && language.getValue().code().equals(value(row,"locale")))
                .findFirst().orElse(null);
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        form.setDisable(true);
        refresh.setDisable(true);
        status.setText("Contacting the translation service…");
        AdminWorkspace.background(task, (result, error) -> {
            form.setDisable(false);
            refresh.setDisable(false);
            if (error == null) { status.setText(""); done.accept(result); }
            else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) expired.run();
            else status.setText("Could not save or read translations. Check the fields and refresh before retrying.");
        });
    }

    private static String value(JsonObject row, String key) { return row.get(key).getAsString(); }
}
