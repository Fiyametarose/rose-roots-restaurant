package com.roseroots.customer.components;

import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.MenuItem.Availability;
import com.roseroots.customer.models.MenuItem.Diet;
import com.roseroots.customer.models.MenuItem.Highlight;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.util.Currency;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class FoodCard extends VBox {
    public FoodCard(MenuItem item, Locale locale, Currency currency, Messages messages, Runnable openDetails) {
        getStyleClass().add("food-card");
        setId("food-card-" + item.id());
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        var cuisine = label(MenuText.label(messages, item.cuisine()), "food-cuisine");
        StackPane.setAlignment(cuisine, Pos.TOP_LEFT);
        StackPane.setMargin(cuisine, new Insets(10, 0, 0, 10));
        var image = new MenuPhoto(item.image(), messages.text("catalog.noPhoto"), 158);
        image.fitAspect(1.87, 136, 227);
        var photo = new StackPane(image, cuisine);

        var title = label(item.name().in(locale), "food-title");
        var price = label(MenuText.price(item.price(), currency, locale), "food-price");
        var summary = label(item.summary().in(locale), "food-summary");
        var tags = new FlowPane(6, 5);
        for (Diet diet : Diet.values()) {
            if (item.safety().dietaryLabels().contains(diet)) {
                tags.getChildren().add(label(MenuText.label(messages, diet), "food-tag"));
            }
        }
        for (Highlight highlight : Highlight.values()) {
            if (item.highlights().contains(highlight)) {
                tags.getChildren().add(label(MenuText.label(messages, highlight), "food-highlight"));
            }
        }
        if (item.spice() != null) {
            tags.getChildren().add(label(MenuText.label(messages, item.spice()), "food-highlight"));
        }
        var available = label(MenuText.label(messages, item.availability()),
                item.availability() == Availability.AVAILABLE ? "food-available" : "food-unavailable");
        var details = CustomerShell.action(messages.text("catalog.viewDetails") + "  ›", true, openDetails);
        details.setId("dish-" + item.id());
        details.setAccessibleText(messages.text("catalog.viewDetails") + ": " + item.name().in(locale));
        details.getStyleClass().add("food-details-action");
        details.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        var footer = new HBox(8, available, new Region(), details);
        HBox.setHgrow(footer.getChildren().get(1), Priority.ALWAYS);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("food-footer");
        var headline = new HBox(8, title, price);
        headline.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        price.setWrapText(false);
        var spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        var body = new VBox(6, headline, summary, tags, spacer, footer);
        body.getStyleClass().add("food-card-body");
        body.setMinWidth(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(photo, body);
        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                openDetails.run();
            }
        });
    }

    public static Label label(String text, String style) {
        var label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }
}
