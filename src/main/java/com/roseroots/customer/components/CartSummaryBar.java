package com.roseroots.customer.components;

import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import javafx.geometry.Pos;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;

public final class CartSummaryBar extends BorderPane {
    private final CustomerState state;
    private final RestaurantSettings settings;
    private final Messages messages;
    private final Label count = new Label();
    private final Label total = new Label();
    private final Button review;

    public CartSummaryBar(CustomerState state, RestaurantSettings settings, Messages messages, Runnable goCart) {
        this.state = state;
        this.settings = settings;
        this.messages = messages;
        getStyleClass().add("cart-summary");
        var title = new Label(messages.text("cart.title"), new InterfaceIcon(InterfaceIcon.Kind.CART));
        title.getStyleClass().add("cart-summary-title");
        count.getStyleClass().add("cart-summary-count");
        total.getStyleClass().add("cart-summary-total");
        total.setWrapText(true);
        var status = new FlowPane(10, 4, title, count, total);
        status.setAlignment(Pos.CENTER_LEFT);
        setCenter(status);
        review = CustomerShell.action(messages.text("cart.review"), false, goCart);
        review.setId("review-cart");
        review.getStyleClass().add("cart-summary-action");
        if (goCart != null) {
            setRight(review);
        }
        BorderPane.setAlignment(review, Pos.CENTER_RIGHT);
        refresh();
    }

    public void refresh() {
        int quantity = state.cart().count();
        count.setText(java.text.NumberFormat.getIntegerInstance(state.locale()).format(quantity) + " "
                + messages.text(quantity == 1 ? "cart.item" : "cart.items"));
        total.setText(messages.text("cart.total") + "  " +
                MenuText.price(com.roseroots.customer.models.CartPricing.calculate(state.cart().total(),
                        settings.cartCharges(), settings.currency()).total(), settings.currency(), state.locale()));
        review.setDisable(quantity == 0);
    }

}
