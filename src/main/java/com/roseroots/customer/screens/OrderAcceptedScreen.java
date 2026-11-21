package com.roseroots.customer.screens;

import com.roseroots.customer.components.CustomerShell;
import com.roseroots.customer.components.FoodCard;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class OrderAcceptedScreen extends StackPane {
    public OrderAcceptedScreen(RestaurantSettings settings, CustomerState state, Ordering.Accepted order,
            Consumer<Locale> language, Runnable home, Runnable menu, Runnable cart,
            Runnable track, Consumer<String> category) {
        var text = new Messages(state.locale());
        var shell = new CustomerShell(settings, state, text, language, home, cart);
        shell.displayCategories(state.menu().catalog(), state.locale(), category);
        shell.showOrdersAccess(track, false);
        var card = new VBox(12);
        card.getStyleClass().add("dish-information");
        if (order != null) {
            card.getChildren().addAll(FoodCard.label(text.text("confirm.accepted"), "catalog-heading"),
                    FoodCard.label(CustomerShell.sessionContext(state, text), "catalog-muted"),
                    FoodCard.label(text.text("orders.number") + " " + order.id(), "dish-section-heading"),
                    FoodCard.label(text.text("custom.visitAllergies") + ": "
                            + MenuText.allergyDisclosure(order.visitAllergy(), text), "dish-description"));
            for (var line : order.lines()) {
                var details = new VBox(4, FoodCard.label(line.pricing().name() + " ×" + line.pricing().quantity(),
                        "dish-section-heading"));
                if (line.billable()) details.getChildren().add(FoodCard.label(
                        price(line.pricing().extendedMinor(), order, state.locale()), "dish-description"));
                if (line.allergy().disclosed() || line.allergy().none()) {
                    details.getChildren().add(FoodCard.label(text.text("custom.itemAllergies") + ": "
                            + MenuText.allergyDisclosure(line.allergy(), text), "dish-description"));
                }
                if (line.review() == Ordering.Review.DECLINED) {
                    details.getChildren().add(FoodCard.label(text.text("confirm.declined"), "customization-warning"));
                } else if (line.review() != Ordering.Review.NOT_REQUIRED && line.review() != Ordering.Review.APPROVED_FOR_PREPARATION) {
                    details.getChildren().add(FoodCard.label(text.text("cart.allergiesPending"), "customization-warning"));
                }
                if (!line.reviewMessage().isBlank()) details.getChildren().add(FoodCard.label(line.reviewMessage(), "dish-description"));
                card.getChildren().add(details);
            }
            card.getChildren().add(FoodCard.label(text.text("orders.orderTotal") + "  " +
                    price(order.totals().totalMinor(), order, state.locale()), "cart-final-total"));
        }
        var trackAction = CustomerShell.action(text.text("confirm.trackOrder"), true, track);
        trackAction.setId("track-accepted-order");
        var more = CustomerShell.action(text.text("orders.more"), false, menu);
        more.setId("accepted-order-more");
        card.getChildren().add(new FlowPane(10, 8, trackAction, more));
        var body = new VBox(card);
        body.getStyleClass().add("catalog-content");
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("catalog-scroll");
        shell.showContent(scroll, false);
        getChildren().add(shell);
    }

    private static String price(long amount, Ordering.Accepted order, Locale locale) {
        return MenuText.price(BigDecimal.valueOf(amount, 2),
                java.util.Currency.getInstance(order.totals().currency()), locale);
    }
}
