package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import static com.roseroots.admin.CatalogControls.*;
import static com.roseroots.admin.CatalogWorkspace.*;

final class DishEditor extends VBox {
    private record Station(String id, String name, boolean active) {
        @Override public String toString() { return name + (active ? "" : " (inactive)"); }
    }

    DishEditor(CatalogWorkspace workspace, JsonObject dish, List<JsonObject> stations) {
        super(14);
        var name = input(dish, "name");
        var description = new TextArea(text(dish, "description"));
        description.setWrapText(true);
        description.setPrefRowCount(3);
        var category = workspace.categoryPicker("Choose a category or nested section", text(dish, "categoryId"));
        var cuisine = options("Ethiopian", "South Sudanese", "International");
        cuisine.setValue(dish == null ? null : cuisineName(text(dish, "cuisine")));
        var price = input(dish, "price");
        var serving = input(dish, "servingSize");
        var profile = dish != null && dish.get("preparation") instanceof JsonObject saved ? saved : null;
        var station = new ComboBox<Station>();
        station.setPromptText("Unconfigured");
        station.setId("dish-preparation-station");
        station.getItems().setAll(stations.stream().map(value -> new Station(text(value, "id"),
                text(value, "name"), value.get("active").getAsBoolean())).toList());
        station.setValue(station.getItems().stream().filter(item -> item.id().equals(text(profile, "stationId")))
                .findFirst().orElse(null));
        var duration = input(profile, "durationSeconds");
        duration.setId("dish-preparation-base");
        var upper = input(profile, "upperDurationSeconds");
        upper.setId("dish-preparation-upper");
        var order = new TextField(dish == null ? "10" : text(dish, "displayOrder"));
        boolean imported = !text(dish, "customerSourceId").isEmpty();
        if (imported) {
            serving.setPromptText("Unknown — manager review");
        }
        var available = options("Available", "Out of stock");
        available.setValue(dish == null || text(dish, "availability").equals("AVAILABLE") ? "Available" : "Out of stock");
        var ingredients = new TextArea(String.join("\n", strings(dish, "ingredients")));
        ingredients.setPrefRowCount(3);
        ingredients.setPromptText("One ingredient per line");
        var allergens = checkboxes(strings(dish, "allergens"), "NUTS", "MILK", "EGGS", "WHEAT", "SOY", "FISH", "SHELLFISH");
        var dietary = checkboxes(strings(dish, "dietaryTags"), "VEGETARIAN", "VEGAN", "GLUTEN_FREE", "DAIRY_FREE", "FASTING_FRIENDLY");
        var customizations = new CustomizationEditor(rows(dish, "customizationGroups"),
                text(dish, "id"), rows(profile, "options"));
        var details = panel("Dish Details", content(
                field("Item name", name),
                field("Category / subcategory", category),
                field("Description", description),
                field("Cuisine", cuisine),
                field("Price (" + workspace.currency() + ")", price),
                field("Serving size", serving),
                field("Preparation station", station),
                field("Base preparation duration (seconds)", duration),
                field("Upper preparation duration (seconds, optional)", upper),
                field("Display order", order),
                field("Availability", available)));
        var photo = new DishPhotoPane(workspace, dish);
        var metadata = panel("Images & Customization", content(
                photo,
                field("Ingredients", ingredients),
                field("Declared allergens", allergens),
                field("Dietary classifications", dietary),
                sourceReview(dish),
                customizations));

        var save = button("Save Dish", () -> {
            try {
                var payload = new JsonObject();
                payload.addProperty("name", required(name.getText(), "Enter an item name."));
                payload.addProperty("description", required(description.getText(), "Enter a description."));
                payload.addProperty("categoryId", required(selectedCategory(category), "Select a category or nested section."));
                payload.addProperty("cuisine", token(required(cuisine.getValue(), "Select a cuisine.")));
                payload.addProperty("price", money(price.getText()));
                payload.addProperty("servingSize", imported ? serving.getText().strip()
                        : required(serving.getText(), "Enter a serving size."));
                payload.addProperty("displayOrder", integer(order.getText(), 0, Integer.MAX_VALUE, "Display order"));
                payload.addProperty("availability", available.getValue().equals("Available") ? "AVAILABLE" : "SOLD_OUT");
                payload.add("ingredients", array(ingredients.getText().lines().map(String::strip)
                        .filter(value -> !value.isEmpty()).toList()));
                payload.add("allergens", selections(allergens));
                payload.add("dietaryTags", selections(dietary));
                var groups = customizations.payload();
                payload.add("customizationGroups", groups);
                var workloads = customizations.workloads(groups);
                if (dish != null) payload.addProperty("expectedVersion", dish.get("version").getAsLong());
                if (dish == null || profile != null || station.getValue() != null || !duration.getText().isBlank()
                        || !upper.getText().isBlank() || !workloads.isEmpty()) {
                    if (station.getValue() == null) throw new IllegalArgumentException("Choose a preparation station.");
                    var preparation = new JsonObject();
                    preparation.addProperty("stationId", station.getValue().id());
                    int base = integer(duration.getText(), 1, 86400, "Base preparation duration");
                    preparation.addProperty("durationSeconds", base);
                    if (!upper.getText().isBlank()) {
                        preparation.addProperty("upperDurationSeconds", integer(upper.getText(), base, 86400, "Upper preparation duration"));
                    }
                    preparation.add("options", workloads);
                    payload.add("preparation", preparation);
                }
                if (!photo.retainedReference().isEmpty()) {
                    payload.addProperty("imageReference", photo.retainedReference());
                }
                workspace.saveDish(dish, payload, photo.draft());
            } catch (IllegalArgumentException error) {
                workspace.invalid(error.getMessage());
            }
        });
        save.disableProperty().bind(photo.busy);
        var cancel = button("Cancel", workspace::returnToMenu);
        cancel.getStyleClass().add("secondary");
        getChildren().addAll(new FlowPane(10, 8, save, cancel), paired(details, metadata));
    }

    private static TextField input(JsonObject row, String key) {
        return new TextField(text(row, key));
    }

    private static VBox sourceReview(JsonObject dish) {
        var review = new VBox(5);
        if (dish != null && dish.get("sourceDetails") instanceof JsonObject source) {
            review.getChildren().add(label("Customer source metadata — unverified", "field-label"));
            review.getChildren().add(label("Reported allergens: " + reported(source, "reportedAllergens")
                    + " · Cross-contact: " + reported(source, "reportedCrossContact"), "muted"));
            review.getChildren().add(label("Reported diets: " + reported(source, "reportedDietaryTags"), "muted"));
            if (source.get("reportedChoiceAllergens") instanceof JsonObject choices && !choices.isEmpty()) {
                choices.keySet().stream().sorted().forEach(choice -> review.getChildren().add(
                        label(choice + ": " + reported(choices, choice), "muted")));
            }
        }
        review.setManaged(!review.getChildren().isEmpty());
        return review;
    }

    private static String reported(JsonObject source, String key) {
        var values = strings(source, key);
        return values.isEmpty() ? "none recorded" : String.join(", ", values);
    }

    private static FlowPane checkboxes(List<String> selected, String... values) {
        var pane = new FlowPane(12, 8);
        Arrays.stream(values).forEach(value -> {
            var choice = new CheckBox(words(value));
            choice.setUserData(value);
            choice.setSelected(selected.contains(value));
            pane.getChildren().add(choice);
        });
        return pane;
    }

    private static JsonArray selections(FlowPane options) {
        return array(options.getChildren().stream().map(CheckBox.class::cast).filter(CheckBox::isSelected)
                .map(box -> (String) box.getUserData()).toList());
    }
}
