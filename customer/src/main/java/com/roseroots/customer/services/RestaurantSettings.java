package com.roseroots.customer.services;

import com.roseroots.customer.models.RestaurantBrand;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import javafx.beans.Observable;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;

public interface RestaurantSettings {
    RestaurantBrand brand();

    String tagline(Locale locale);

    List<Locale> languages();

    Currency currency();
    default Observable changes() { return null; }

    default void observeWhileAttached(Node node, Runnable refresh) {
        var source = changes();
        if (source == null) return;
        InvalidationListener listener = ignored -> refresh.run();
        node.sceneProperty().addListener((property, previous, next) -> {
            if (previous != null) source.removeListener(listener);
            if (next != null) {
                source.addListener(listener);
                refresh.run();
            }
        });
    }

    default List<com.roseroots.customer.models.CartPricing.Rule> cartCharges() { return List.of(); }
}
