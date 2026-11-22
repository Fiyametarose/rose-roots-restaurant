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

    CustomizationEditor(List<JsonObject> existing, String dishId, List<JsonObject> workloads) {
        super(8);
        for (var group : existing) {
            groups.getChildren().add(new Group(group, dishId, workloads));
        }
        getChildren().addAll(label("Customization & extras", "field-label"), groups,
                button("Add Option Group", () -> groups.getChildren().add(new Group(null, "", List.of()))));
    }

    JsonArray payload() {
        var result = new JsonArray();
        for (var node : groups.getChildren()) {
            result.add(((Group) node).payload());
        }
        return result;
    }

    JsonArray workloads(JsonArray payload) {
        var result = new JsonArray();
        for (int index = 0; index < payload.size(); index++) {
            ((Group) groups.getChildren().get(index)).appendWorkloads(payload.get(index).getAsJsonObject(), result);
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
        private final TextArea workload = new TextArea();

        Group(JsonObject original, String dishId, List<JsonObject> workloads) {
            this.original = original;
            name = new TextField(text(original, "name"));
            minimum = new TextField(original == null ? "0" : text(original, "minimum"));
            maximum = new TextField(original == null ? "1" : text(original, "maximum"));
            selection.setValue(original == null ? "Extra" : words(text(original, "selection")));
            choices.setPrefRowCount(3);
            choices.setText(String.join("\n", rows(original, "choices").stream()
                    .map(Group::choiceText).toList()));
            choices.setPromptText("Extra injera | 40.00");
            workload.setPrefRowCount(2);
            workload.setPromptText("Choice name | lower seconds | upper seconds");
            if (original != null) {
                String group = canonical(text(original, "id"), dishId + "/group/");
                var lines = new java.util.ArrayList<String>();
                for (var option : rows(original, "choices")) {
                    String id = canonical(text(option, "id"), group + "/option/");
                    workloads.stream().filter(row -> group.equals(text(row, "groupId")) && id.equals(text(row, "optionId")))
                            .findFirst().ifPresent(row -> lines.add(text(option, "name") + " | "
                                    + text(row, "lowerSeconds") + " | " + text(row, "upperSeconds")));
                }
                workload.setText(String.join("\n", lines));
            }
            var advanced = new TitledPane("Advanced option workload", content(
                    field("Additional seconds per item (choice name | lower | upper)", workload)));
            advanced.setExpanded(false);
            setText(original == null ? "New option group" : text(original, "name"));
            name.textProperty().addListener((obs, before, after) -> setText(after.isBlank() ? "Option group" : after));
            setExpanded(original == null);
            setContent(content(field("Group name", name), field("Type", selection),
                    field("Minimum selections", minimum), field("Maximum selections", maximum),
                    field("Choices (name | price adjustment | optional allergen codes)", choices),
                    advanced,
                    button("Remove Group", () -> groups.getChildren().remove(this))));
        }

        void appendWorkloads(JsonObject group, JsonArray result) {
            var seen = new java.util.HashSet<String>();
            for (var line : workload.getText().lines().filter(value -> !value.isBlank()).toList()) {
                var parts = line.split("\\|", -1);
                if (parts.length != 3 || !seen.add(parts[0].strip())) {
                    throw new IllegalArgumentException("Enter each option workload once as Choice name | lower | upper.");
                }
                var option = rows(group, "choices").stream().filter(row -> text(row, "name").equals(parts[0].strip()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Workload must name an option in this group."));
                int lower = integer(parts[1], 0, 86400, "Option lower workload");
                int upper = integer(parts[2], lower, 86400, "Option upper workload");
                var row = new JsonObject();
                row.addProperty("groupId", text(group, "id"));
                row.addProperty("optionId", text(option, "id"));
                row.addProperty("lowerSeconds", lower);
                row.addProperty("upperSeconds", upper);
                result.add(row);
            }
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

    private static String canonical(String value, String namespace) {
        return value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") ? value
                : UUID.nameUUIDFromBytes((namespace + value).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
