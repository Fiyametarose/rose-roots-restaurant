package com.roseroots.customer.app;

import com.roseroots.customer.navigation.Navigator;
import com.roseroots.customer.navigation.Route;
import com.roseroots.customer.screens.DishDetailsScreen;
import com.roseroots.customer.screens.CartScreen;
import com.roseroots.customer.screens.MenuScreen;
import com.roseroots.customer.screens.WelcomeScreen;
import com.roseroots.customer.services.LocalRestaurantSettings;
import com.roseroots.customer.services.DiningSessionService;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.services.mock.LocalDiningSessionService;
import com.roseroots.customer.services.mock.LocalTakeawaySessionService;
import com.roseroots.customer.services.api.SpringBootTakeawaySessionService;
import com.roseroots.customer.services.TakeawaySessionService;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.components.CustomerShell;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.Messages;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class CustomerApplication extends Application {
    private static final double DESIGN_WIDTH = 1280;
    private static final double DESIGN_HEIGHT = 800;

    @Override
    public void start(Stage stage) {
        Font.loadFont(Objects.requireNonNull(getClass().getResource(
                "/fonts/NotoSansEthiopic-Regular.ttf")).toExternalForm(), 16);
        var state = new CustomerState();
        var settings = new LocalRestaurantSettings();
        var args = getParameters().getRaw();
        boolean demoTable = args.contains("--demo-table");
        boolean demoTakeaway = demoTable || args.contains("--demo-takeaway");
        var menuService = new MockMenuService(demoTakeaway);
        DiningSessionService sessions = new LocalDiningSessionService(demoTable);
        TakeawaySessionService takeaway = demoTakeaway ? new LocalTakeawaySessionService("demo-takeaway-terminal")
                : new SpringBootTakeawaySessionService(mode -> CompletableFuture.failedFuture(
                        new IllegalStateException("Terminal transport is not configured")));
        state.setLocale(settings.languages().getFirst());
        var content = new StackPane();
        var navigator = new Navigator(content);
        navigator.register(Route.WELCOME, () -> {
            var welcome = new WelcomeScreen(settings, state, mode -> {
                var messages = new Messages(state.locale());
                boolean switchingWithDraft = state.cart().count() > 0 && mode != state.serviceMode();
                if (switchingWithDraft && !confirmDiscard(stage, messages)) {
                    return;
                }
                openSession(stage, content, navigator, state, mode, sessions, takeaway, switchingWithDraft);
            });
            return welcome;
        });
        Runnable goHome = () -> navigator.show(Route.WELCOME);
        Runnable goMenu = () -> navigator.show(Route.MENU);
        Runnable goCart = () -> navigator.show(Route.CART);
        var editing = new java.util.concurrent.atomic.AtomicReference<java.util.UUID>();
        java.util.function.Consumer<String> chooseCategory = category -> {
            state.menu().selectCategory(category);
            state.menu().rememberScroll(0);
            goMenu.run();
        };
        navigator.register(Route.CART, () -> new CartScreen(settings, state, com.roseroots.customer.services.WaitEstimateService.unavailable(), locale -> {
            state.setLocale(locale);
            goCart.run();
        }, goHome, goMenu, id -> {
            editing.set(id);
            navigator.show(Route.DISH_DETAILS);
        }, chooseCategory));
        navigator.register(Route.MENU, () -> new MenuScreen(settings, state, menuService, locale -> {
            state.setLocale(locale);
            goMenu.run();
        }, goHome, itemId -> {
            editing.set(null);
            state.menu().selectItem(itemId);
            navigator.show(Route.DISH_DETAILS);
        }, goCart));
        navigator.register(Route.DISH_DETAILS, () -> {
            var lineId = editing.get();
            var line = lineId == null ? null : state.cart().line(lineId);
            var item = line == null ? state.menu().catalog().item(state.menu().itemId()) : line.item();
            return new DishDetailsScreen(settings, state, item, locale -> {
                state.setLocale(locale);
                navigator.show(Route.DISH_DETAILS);
            }, goHome, lineId == null ? goMenu : goCart, chooseCategory, goCart, lineId);
        });
        navigator.show(Route.WELCOME);

        var workArea = Screen.getPrimary().getVisualBounds();
        var scene = new Scene(content, Math.min(DESIGN_WIDTH, workArea.getWidth() - 32),
                Math.min(DESIGN_HEIGHT, workArea.getHeight() - 80));
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/styles/theme.css")).toExternalForm());

        stage.setTitle(settings.brand().windowTitle());
        stage.setMinWidth(480);
        stage.setMinHeight(320);
        stage.setScene(scene);
        stage.setFullScreen(getParameters().getRaw().contains("--fullscreen"));
        stage.show();
        stage.centerOnScreen();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static void openSession(Stage stage, StackPane content, Navigator navigator, CustomerState state,
                                    ServiceMode mode, DiningSessionService dining, TakeawaySessionService takeaway,
                                    boolean discardConfirmed) {
        var messages = new Messages(state.locale());
        content.setDisable(true);
        var request = mode == ServiceMode.TAKEAWAY ? takeaway.openOrResume() : dining.openOrResume(mode);
        request.whenComplete((session, error) -> Platform.runLater(() -> {
            content.setDisable(false);
            if (error != null || session == null || (state.wouldDiscardCart(session) && !session.isOpen())) {
                var welcome = content.getChildren().getFirst();
                var message = new javafx.scene.control.Label(messages.text(mode == ServiceMode.TAKEAWAY
                        ? "session.takeawayRetry" : "session.connectionFailed"));
                message.setWrapText(true);
                var overlay = new StackPane();
                overlay.getStyleClass().add("filter-overlay");
                welcome.setDisable(true);
                Runnable dismiss = () -> {
                    content.getChildren().remove(overlay);
                    welcome.setDisable(false);
                };
                var retry = CustomerShell.action(messages.text("session.retry"), true, () -> {
                    dismiss.run();
                    openSession(stage, content, navigator, state, mode, dining, takeaway, discardConfirmed);
                });
                retry.setId("retry-session");
                var cancel = CustomerShell.action(messages.text("catalog.cancelFilters"), false, dismiss);
                cancel.setId("cancel-session-retry");
                var buttons = new javafx.scene.layout.FlowPane(8, 6, retry, cancel);
                var panel = new javafx.scene.layout.VBox(14, message, buttons);
                panel.getStyleClass().add("advanced-filter-panel");
                panel.setMaxWidth(420);
                panel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                overlay.getChildren().add(panel);
                overlay.setOnKeyPressed(event -> {
                    if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        dismiss.run();
                        event.consume();
                    }
                });
                content.getChildren().add(overlay);
                retry.requestFocus();
                return;
            }
            if (state.activateSession(session, discardConfirmed)
                    || (confirmDiscard(stage, messages) && state.activateSession(session, true))) {
                navigator.show(Route.MENU);
            }
        }));
    }

    private static boolean confirmDiscard(Stage stage, Messages messages) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, messages.text("session.discardPrompt"),
                ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle(messages.text("session.switchTitle"));
        alert.setHeaderText(messages.text("session.switchTitle"));
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
