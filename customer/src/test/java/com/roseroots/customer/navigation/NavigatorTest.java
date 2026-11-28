package com.roseroots.customer.navigation;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

class NavigatorTest {
    @Test
    void replacesThePreviousScreen() {
        var host = new StackPane();
        var previous = new StackPane();
        var welcome = new StackPane();
        host.getChildren().add(previous);
        var navigator = new Navigator(host);
        navigator.register(Route.WELCOME, () -> welcome);

        navigator.show(Route.WELCOME);

        assertSame(welcome, host.getChildren().getFirst());
        assertNull(previous.getParent());
    }

    @Test
    void rejectsUnregisteredAndDuplicateRoutes() {
        var navigator = new Navigator(new StackPane());
        assertThrows(IllegalArgumentException.class, () -> navigator.show(Route.WELCOME));

        navigator.register(Route.WELCOME, StackPane::new);
        assertThrows(IllegalArgumentException.class,
                () -> navigator.register(Route.WELCOME, StackPane::new));
    }
}
