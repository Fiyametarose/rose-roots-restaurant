package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class CatalogControls {
    private CatalogControls() {}

    static String text(JsonObject row, String key) {
        if (row == null) {
            return "";
        }
        var value = row.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static List<JsonObject> rows(JsonObject row, String key) {
        var result = new ArrayList<JsonObject>();
        if (row != null && row.get(key) instanceof JsonArray values) {
            for (var value : values) {
                if (value instanceof JsonObject object) {
                    result.add(object);
                }
            }
        }
        return result;
    }

    static List<String> strings(JsonObject row, String key) {
        if (row != null && row.get(key) instanceof JsonArray values) {
            return values.asList().stream().map(JsonElement::getAsString).toList();
        }
        return List.of();
    }

    static JsonArray array(List<String> items) {
        var values = new JsonArray();
        items.forEach(values::add);
        return values;
    }

    static String words(String value) {
        String formatted = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return formatted.isEmpty() ? formatted : Character.toUpperCase(formatted.charAt(0)) + formatted.substring(1);
    }

    static boolean matchesText(String value, String search) {
        return value.toLowerCase(Locale.ROOT).contains(search.strip().toLowerCase(Locale.ROOT));
    }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }

    static VBox field(String title, Node control) {
        Label label = label(title, "field-label");
        label.setLabelFor(control);
        return new VBox(5, label, control);
    }

    static VBox panel(String title, Node body) {
        Label heading = label(title, "panel-title");
        heading.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(heading, body);
        box.getStyleClass().add("panel");
        return box;
    }

    static VBox content(Node... children) {
        VBox box = new VBox(12, children);
        box.getStyleClass().add("catalog-content");
        return box;
    }

    static Button button(String title, Runnable action) {
        Button button = new Button(title);
        button.setOnAction(event -> action.run());
        return button;
    }

    static ComboBox<String> options(String... values) {
        var box = new ComboBox<String>();
        box.getItems().addAll(values);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    static TableView<JsonObject> table() {
        var table = new TableView<JsonObject>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setMinHeight(240);
        table.setPrefHeight(420);
        table.setPlaceholder(label("No matches.", "muted"));
        return table;
    }

    static String cuisineName(String value) {
        return "SOUTH_SUDANESE".equals(value) ? "South Sudanese" : words(value);
    }

    static TableColumn<JsonObject, String> column(String title, double width, Function<JsonObject, String> value) {
        var column = new TableColumn<JsonObject, String>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(value.apply(row.getValue())));
        return column;
    }

    static GridPane paired(Node first, Node second) {
        var grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.add(first, 0, 0);
        grid.add(second, 0, 1);
        GridPane.setHgrow(first, Priority.ALWAYS);
        GridPane.setHgrow(second, Priority.ALWAYS);
        GridPane.setValignment(first, VPos.TOP);
        GridPane.setValignment(second, VPos.TOP);
        grid.widthProperty().addListener((observable, before, after) -> {
            boolean wide = after.doubleValue() >= 805;
            GridPane.setConstraints(second, wide ? 1 : 0, wide ? 0 : 1);
        });
        return grid;
    }
}
