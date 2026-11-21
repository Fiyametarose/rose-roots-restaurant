package com.roseroots.customer.components;

import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.ConfiguredDish;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

public final class DishCustomizer extends VBox {
    public DishCustomizer(MenuItem item, UUID lineId, CustomerState state, RestaurantSettings settings,
                          Messages messages, Runnable saved) {
        super(10);
        getStyleClass().add("dish-information");
        var line = lineId == null ? null : state.cart().line(lineId);
        var prior = line == null ? null : line.dish();
        var defaults = prior == null ? ConfiguredDish.defaults(item) : prior.selections();
        int[] quantity = {prior == null ? 1 : prior.quantity()};
        var count = FoodCard.label(Integer.toString(quantity[0]), "dish-section-heading");
        count.setId("dish-quantity");
        var selectors = new LinkedHashMap<String, Map<String, javafx.beans.value.ObservableBooleanValue>>();
        var warning = FoodCard.label("", "dish-allergen-warning");
        warning.setId("allergy-conflict");
        warning.managedProperty().bind(warning.visibleProperty());
        var notice = FoodCard.label("", "catalog-muted");
        notice.setId("customization-status");
        notice.managedProperty().bind(notice.textProperty().isNotEmpty());
        var total = FoodCard.label("", "dish-price");
        total.setId("customization-total");
        var selectedAllergens = new EnumMap<AllergyDeclaration.Allergen, CheckBox>(AllergyDeclaration.Allergen.class);
        var disclosure = prior == null ? AllergyDeclaration.unreviewed() : prior.allergy();
        var other = notes(messages.text("custom.other"), disclosure.other(), "other-allergies");
        var instructions = notes(messages.text("custom.instructions"), prior == null ? "" : prior.instructions(),
                "preparation-instructions");
        var none = new CheckBox(messages.text("custom.none"));
        none.setId("no-known-allergies");
        none.setSelected(disclosure.none());
        var allergies = new FlowPane(10, 5);
        for (var allergen : AllergyDeclaration.Allergen.values()) {
            var check = new CheckBox(allergenLabel(messages, allergen));
            check.setId("declare-" + allergen.name().toLowerCase(java.util.Locale.ROOT));
            check.setSelected(disclosure.allergens().contains(allergen));
            check.disableProperty().bind(none.selectedProperty());
            selectedAllergens.put(allergen, check);
            allergies.getChildren().add(check);
        }
        other.disableProperty().bind(none.selectedProperty());
        java.util.function.Supplier<AllergyDeclaration> declaration = () -> new AllergyDeclaration(none.isSelected(),
                none.isSelected() ? Set.of() : selectedAllergens.entrySet().stream()
                        .filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).collect(Collectors.toSet()),
                none.isSelected() ? "" : other.getText());
        java.util.function.Supplier<ConfiguredDish> configured = () -> new ConfiguredDish(item, quantity[0],
                selectors.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, group -> group.getValue()
                        .entrySet().stream().filter(entry -> entry.getValue().get()).map(Map.Entry::getKey)
                        .collect(Collectors.toSet()))), instructions.getText(), declaration.get());

        var add = CustomerShell.action(messages.text(lineId == null ? "custom.add" : "custom.save"), true, () -> {
            if (state.saveDish(lineId, configured.get())) {
                notice.setText(messages.text(lineId == null ? "custom.added" : "custom.saved"));
                saved.run();
            } else {
                notice.setText(messages.text("custom.notAdded"));
            }
        });
        add.setId("add-to-draft");
        add.setMaxWidth(Double.MAX_VALUE);
        add.getStyleClass().add("dish-add-draft");
        var minus = CustomerShell.action("−", false, () -> count.setText(Integer.toString(--quantity[0])));
        minus.setAccessibleText(messages.text("custom.less"));
        var plus = CustomerShell.action("+", false, () -> count.setText(Integer.toString(++quantity[0])));
        plus.setAccessibleText(messages.text("custom.more"));
        plus.setId("increase-dish-quantity");
        Runnable update = () -> {
            minus.setDisable(quantity[0] == 1);
            plus.setDisable(quantity[0] == ConfiguredDish.MAX_QUANTITY);
            try {
                var dish = configured.get();
                total.setText(messages.text("cart.total") + "  " + MenuText.price(dish.total(), settings.currency(), state.locale()));
                var own = dish.review(dish.allergy());
                var visit = dish.review(state.sessionAllergy());
                boolean hasSession = state.terminalSession() != null && state.terminalSession().allowsCart();
                boolean available = item.availability() == MenuItem.Availability.AVAILABLE;
                var direct = java.util.stream.Stream.concat(own.ingredients().stream(), visit.ingredients().stream())
                        .distinct().map(value -> MenuText.label(messages, value)).sorted().collect(Collectors.joining(", "));
                var cross = java.util.stream.Stream.concat(own.crossContact().stream(), visit.crossContact().stream())
                        .distinct().map(value -> MenuText.label(messages, value)).sorted().collect(Collectors.joining(", "));
                var status = !hasSession ? messages.text("custom.noSession")
                        : !available ? messages.text("custom.unavailable") : "";
                var detail = java.util.stream.Stream.of(
                        direct.isBlank() ? "" : messages.text("confirm.directConflict") + ": " + direct,
                        cross.isBlank() ? "" : messages.text("confirm.possibleContact") + ": " + cross,
                        status).filter(value -> !value.isBlank()).collect(Collectors.joining("\n"));
                warning.setText(detail);
                warning.setVisible(!detail.isEmpty());
                add.setDisable(!hasSession || !available);
            } catch (IllegalArgumentException invalid) {
                warning.setText(messages.text("custom.selectionRequired"));
                warning.setVisible(true);
                add.setDisable(true);
            }
        };
        getChildren().addAll(FoodCard.label(messages.text("custom.title"), "dish-panel-title"),
                new FlowPane(9, 5, FoodCard.label(messages.text("custom.quantity"), "dish-section-heading"),
                        minus, count, plus));
        for (var group : item.customizationGroups()) {
            var buttons = new FlowPane(8, 5);
            var props = new LinkedHashMap<String, javafx.beans.value.ObservableBooleanValue>();
            selectors.put(group.id(), props);
            var radios = group.minimum() == 1 && group.maximum() == 1 ? new ToggleGroup() : null;
            for (var choice : group.choices()) {
                String label = choice.name().in(state.locale());
                if (choice.priceAdjustment().signum() != 0) {
                    label += " (" + MenuText.price(choice.priceAdjustment(), settings.currency(), state.locale()) + ")";
                }
                boolean selected = defaults.getOrDefault(group.id(), Set.of()).contains(choice.id());
                if (radios != null) {
                    var option = new RadioButton(label);
                    option.setToggleGroup(radios);
                    option.setSelected(selected);
                    option.setId("option-" + choice.id());
                    props.put(choice.id(), option.selectedProperty());
                    buttons.getChildren().add(option);
                } else {
                    var option = new CheckBox(label);
                    option.setSelected(selected);
                    option.setId("option-" + choice.id());
                    props.put(choice.id(), option.selectedProperty());
                    buttons.getChildren().add(option);
                }
            }
            getChildren().addAll(FoodCard.label(group.name().in(state.locale()), "dish-section-heading"), buttons);
        }
        var remember = CustomerShell.action(messages.text("custom.remember"), false, () -> {
            state.rememberAllergy(declaration.get());
            notice.setText(messages.text("custom.remembered"));
            update.run();
        });
        remember.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> state.terminalSession() == null || !state.terminalSession().allowsCart()
                        || (!declaration.get().none() && !declaration.get().disclosed()),
                java.util.stream.Stream.concat(selectedAllergens.values().stream().map(CheckBox::selectedProperty),
                        java.util.stream.Stream.of(none.selectedProperty(), other.textProperty()))
                        .toArray(javafx.beans.Observable[]::new)));
        getChildren().addAll(FoodCard.label(messages.text("custom.itemAllergies"), "dish-panel-title"),
                FoodCard.label(messages.text("confirm.itemAllergyScope"), "catalog-muted"),
                none, allergies, other, instructions, FoodCard.label(messages.text("custom.note"), "catalog-muted"),
                remember, warning, total, add, notice);
        selectors.values().forEach(group -> group.values().forEach(property -> property.addListener(o -> update.run())));
        count.textProperty().addListener(o -> update.run());
        selectedAllergens.values().forEach(box -> box.selectedProperty().addListener(o -> update.run()));
        none.selectedProperty().addListener(o -> update.run());
        other.textProperty().addListener(o -> update.run());
        instructions.textProperty().addListener(o -> update.run());
        update.run();
    }

    private static TextArea notes(String prompt, String initial, String id) {
        var area = new TextArea(initial);
        area.setId(id);
        area.setPromptText(prompt);
        area.setAccessibleText(prompt);
        area.setPrefRowCount(2);
        area.setWrapText(true);
        area.setTextFormatter(new TextFormatter<String>(edit -> edit.getControlNewText().length() <= 240 ? edit : null));
        return area;
    }

    public static String allergenLabel(Messages messages, AllergyDeclaration.Allergen value) {
        return messages.text("catalog.allergen." + value.name().toLowerCase(java.util.Locale.ROOT));
    }
}
