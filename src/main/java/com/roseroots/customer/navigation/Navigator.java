package com.roseroots.customer.navigation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;

public final class Navigator {
    private final StackPane host;
    private final Map<Route, Supplier<? extends Parent>> screens = new EnumMap<>(Route.class);

    public Navigator(StackPane host) {
        this.host = Objects.requireNonNull(host);
    }

    public void register(Route route, Supplier<? extends Parent> screenFactory) {
        if (screens.putIfAbsent(route, Objects.requireNonNull(screenFactory)) != null) {
            throw new IllegalArgumentException("Route already registered: " + route);
        }
    }

    public void show(Route route) {
        var screenFactory = screens.get(route);
        if (screenFactory == null) {
            throw new IllegalArgumentException("Unknown route: " + route);
        }
        host.getChildren().setAll(Objects.requireNonNull(screenFactory.get()));
    }
}
