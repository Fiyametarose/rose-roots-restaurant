package com.roseroots.customer.screens;

import com.roseroots.customer.components.CustomerShell;
import com.roseroots.customer.components.DishCustomizer;
import com.roseroots.customer.components.FoodCard;
import com.roseroots.customer.components.InterfaceIcon;
import com.roseroots.customer.components.MenuPhoto;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.MenuItem.Allergen;
import com.roseroots.customer.models.MenuItem.Diet;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class DishDetailsScreen extends StackPane {
    public DishDetailsScreen(RestaurantSettings settings, CustomerState state, MenuItem item,
                             Consumer<Locale> onLanguage, Runnable goHome, Runnable goBack,
                             Consumer<String> selectCategory, Runnable goCart, java.util.UUID lineId) {
        var locale = state.locale();
        var messages = new Messages(locale);
        var shell = new CustomerShell(settings, state, messages, onLanguage, goHome, goCart);
        var catalog = state.menu().catalog();
        shell.displayCategories(catalog, locale, selectCategory);
        var back = CustomerShell.action(messages.text(lineId == null ? "catalog.backToMenu" : "cart.review"), false, goBack);
        back.setGraphic(new InterfaceIcon(InterfaceIcon.Kind.BACK));
        back.setId("back-to-menu");
        var title = FoodCard.label(messages.text("catalog.dishDetails"), "catalog-heading");
        var titleRow = new FlowPane(14, 4, back, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getStyleClass().add("catalog-title-row");
        var content = new VBox(14, titleRow);
        content.getStyleClass().add("catalog-content");
        var scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("catalog-scroll");

        if (item == null) {
            content.getChildren().add(FoodCard.label(messages.text("catalog.itemUnavailable"), "catalog-muted"));
        } else {
            var category = catalog.category(item.categoryId());
            var photo = new MenuPhoto(item.image(), messages.text("catalog.noPhoto"), 345);
            photo.fitAspect(1.53, 195, 365);
            var cuisine = FoodCard.label(MenuText.label(messages, item.cuisine()), "food-cuisine");
            StackPane.setAlignment(cuisine, Pos.TOP_LEFT);
            StackPane.setMargin(cuisine, new Insets(14, 0, 0, 14));
            var photoFrame = new StackPane(photo, cuisine);
            var name = FoodCard.label(item.name().in(locale), "dish-name");
            var meta = FoodCard.label(category == null ? "" : category.name().in(locale), "catalog-muted");
            var price = FoodCard.label(MenuText.price(item.price(), settings.currency(), locale), "dish-price");
            var available = FoodCard.label(MenuText.label(messages, item.availability()),
                    item.availability() == MenuItem.Availability.AVAILABLE ? "food-available" : "food-unavailable");
            var description = FoodCard.label(item.description().in(locale), "dish-description");
            var nameRow = new HBox(12, name, price);
            nameRow.setAlignment(Pos.BASELINE_LEFT);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            var attributes = new FlowPane(10, 5, meta, available);
            attributes.setAlignment(Pos.CENTER_LEFT);
            var overview = new VBox(9, photoFrame, nameRow, attributes, description);

            var ingredients = item.ingredients().stream().map(value -> value.in(locale)).collect(Collectors.joining(", "));
            var safety = item.safety();
            if (catalog.developmentFixtures() && !safety.allergenDataComplete()) {
                ingredients = "";
            }
            var info = new VBox(10, FoodCard.label(messages.text("catalog.dishInformation"), "dish-panel-title"));
            info.getStyleClass().add("dish-information");
            info.getChildren().addAll(
                    section(messages, "catalog.ingredients", ingredients),
                    section(messages, "catalog.dietary", labels(safety.dietaryLabels(), Diet.values(), messages)),
                    allergenSection(messages, "catalog.contains", safety.contains()),
                    allergenSection(messages, "catalog.crossContact", safety.possibleCrossContact()));
            if (!safety.allergenDataComplete()) {
                info.getChildren().add(FoodCard.label(messages.text("catalog.incompleteAllergens"), "dish-description"));
            }
            if (item.spice() != null) {
                info.getChildren().add(section(messages, "catalog.spice", MenuText.label(messages, item.spice())));
            }
            if (item.preparationTime() != null) {
                var minutes = java.text.NumberFormat.getIntegerInstance(locale).format(item.preparationTime().toMinutes());
                info.getChildren().add(section(messages, "catalog.preparation",
                        minutes + " " + messages.text("catalog.minutes") + " · " + messages.text("catalog.preparationNote")));
            }
            overview.getChildren().add(info);
            var customizer = new DishCustomizer(item, lineId, state, settings, messages, () -> {
                shell.refreshCart();
                if (lineId != null) {
                    goCart.run();
                }
            });
            var columns = new FlowPane(18, 18, overview, customizer);
            columns.getStyleClass().add("dish-columns");
            scroller.viewportBoundsProperty().addListener((property, previous, next) -> {
                double usable = Math.max(0, next.getWidth() - 44);
                double columnSpace = Math.max(0, usable - 20);
                overview.setPrefWidth(usable < 670 ? usable : columnSpace * .45);
                customizer.setPrefWidth(usable < 670 ? usable : columnSpace * .55);
            });
            content.getChildren().add(columns);
        }
        shell.showContent(scroller);
        getChildren().add(shell);
    }

    private static VBox section(Messages messages, String heading, String value) {
        var section = new VBox(3, FoodCard.label(messages.text(heading), "dish-section-heading"),
                FoodCard.label(value.isBlank() ? messages.text("catalog.noneListed") : value, "dish-description"));
        section.getStyleClass().add("dish-section");
        return section;
    }

    private static VBox allergenSection(Messages messages, String heading, Set<Allergen> allergens) {
        var value = labels(allergens, Allergen.values(), messages);
        return section(messages, heading, value.isBlank() ? messages.text("catalog.noAllergensRecorded") : value);
    }

    private static <E extends Enum<E>> String labels(Set<E> values, E[] order, Messages messages) {
        return Arrays.stream(order).filter(values::contains).map(value -> MenuText.label(messages, value))
                .collect(Collectors.joining(", "));
    }
}
