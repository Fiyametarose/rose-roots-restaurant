package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import static com.roseroots.admin.CatalogControls.*;
import static com.roseroots.admin.CatalogWorkspace.*;

final class CustomizationEditor extends VBox {
    private final VBox groups = new VBox(8);

    CustomizationEditor(List<JsonObject> existing) {
        super(8);
        for (var group : existing) {
            groups.getChildren().add(new Group(group));
        }
        getChildren().addAll(label("Customization & extras", "field-label"), groups,
                button("Add Option Group", () -> groups.getChildren().add(new Group(null))));
    }

    JsonArray payload() {
        var result = new JsonArray();
        for (var node : groups.getChildren()) {
            result.add(((Group) node).payload());
        }
        return result;
    }

    private final class Group extends TitledPane {
        private final JsonObject original;
        private final TextField name;
        private final ComboBox<String> selection = options("Portion", "Spice", "Side", "Extra");
        private final TextField minimum;
        private final TextField maximum;
        private final TextArea choices = new TextArea();

        Group(JsonObject original) {
            this.original = original;
            name = new TextField(text(original, "name"));
            minimum = new TextField(original == null ? "0" : text(original, "minimum"));
            maximum = new TextField(original == null ? "1" : text(original, "maximum"));
            selection.setValue(original == null ? "Extra" : words(text(original, "selection")));
            choices.setPrefRowCount(3);
            choices.setText(String.join("\n", rows(original, "choices").stream()
                    .map(Group::choiceText).toList()));
            choices.setPromptText("Extra injera | 40.00");
            setText(original == null ? "New option group" : text(original, "name"));
            name.textProperty().addListener((obs, before, after) -> setText(after.isBlank() ? "Option group" : after));
            setExpanded(original == null);
            setContent(content(field("Group name", name), field("Type", selection),
                    field("Minimum selections", minimum), field("Maximum selections", maximum),
                    field("Choices (name | price adjustment | optional allergen codes)", choices),
                    button("Remove Group", () -> groups.getChildren().remove(this))));
        }

        JsonObject payload() {
            var group = new JsonObject();
            group.addProperty("id", original == null ? UUID.randomUUID().toString() : text(original, "id"));
            group.addProperty("name", required(name.getText(), "Name every customization group."));
            group.addProperty("selection", token(selection.getValue()));
            int min = integer(minimum.getText(), 0, 50, "Minimum selections");
            int max = integer(maximum.getText(), 1, 50, "Maximum selections");
            var options = new JsonArray();
            var existingNames = new java.util.HashSet<String>();
            for (String line : choices.getText().lines().filter(value -> !value.isBlank()).toList()) {
                String[] parts = line.split("\\|", -1);
                if (parts.length < 2 || parts.length > 3 || !parts[1].strip().matches("-?[0-9]{1,10}(\\.[0-9]{1,2})?")) {
                    throw new IllegalArgumentException("Use Name | 0.00 and optionally add | MILK, EGGS.");
                }
                String name = required(parts[0], "Name every choice.");
                if (!existingNames.add(name)) {
                    throw new IllegalArgumentException("Use unique choice names within each group.");
                }
                var choice = new JsonObject();
                choice.addProperty("id", rows(original, "choices").stream()
                        .filter(old -> text(old, "name").equals(name)).map(old -> text(old, "id")).findFirst()
                        .orElseGet(() -> UUID.randomUUID().toString()));
                choice.addProperty("name", name);
                choice.addProperty("priceAdjustment", new BigDecimal(parts[1].strip()));
                var allergens = new JsonArray();
                if (parts.length == 3 && !parts[2].isBlank()) {
                    for (String raw : parts[2].split(",", -1)) {
                        String code = raw.strip().toUpperCase(java.util.Locale.ROOT);
                        if (!java.util.Set.of("NUTS", "MILK", "EGGS", "WHEAT", "SOY", "FISH", "SHELLFISH").contains(code)) {
                            throw new IllegalArgumentException("Use known catalog allergen codes for " + name + ".");
                        }
                        allergens.add(code);
                    }
                }
                choice.add("allergens", allergens);
                options.add(choice);
            }
            if (min > max || max > options.size()) {
                throw new IllegalArgumentException("Selection limits must fit the number of choices.");
            }
            group.addProperty("minimum", min);
            group.addProperty("maximum", max);
            group.add("choices", options);
            return group;
        }

        private static String choiceText(JsonObject choice) {
            JsonArray allergens = choice.getAsJsonArray("allergens");
            String base = text(choice, "name") + " | " + text(choice, "priceAdjustment");
            return allergens == null || allergens.isEmpty() ? base : base + " | " + allergens.asList().stream()
                    .map(com.google.gson.JsonElement::getAsString).collect(java.util.stream.Collectors.joining(", "));
        }
    }
}
