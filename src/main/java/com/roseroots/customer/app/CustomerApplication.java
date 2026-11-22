package com.roseroots.customer.app;

import com.roseroots.customer.navigation.Navigator;
import com.roseroots.customer.navigation.Route;
import com.roseroots.customer.screens.DishDetailsScreen;
import com.roseroots.customer.screens.CartScreen;
import com.roseroots.customer.screens.MenuScreen;
import com.roseroots.customer.screens.OrdersScreen;
import com.roseroots.customer.screens.OrderAcceptedScreen;
import com.roseroots.customer.screens.WelcomeScreen;
import com.roseroots.customer.services.LocalRestaurantSettings;
import com.roseroots.customer.services.DiningSessionService;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.services.mock.LocalDiningSessionService;
import com.roseroots.customer.services.mock.LocalTakeawaySessionService;
import com.roseroots.customer.services.LocalDeviceConfiguration;
import com.roseroots.customer.services.LocalDraftSessionService;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.services.TakeawaySessionService;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.components.CustomerShell;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.state.OrderConfirmation;
import com.roseroots.customer.services.OrderService;
import com.roseroots.customer.services.OrderTrackingService;
import com.roseroots.customer.services.api.BackendAddress;
import com.roseroots.customer.services.api.SpringBootOrderService;
import com.roseroots.customer.services.api.ConnectedTerminal;
import com.roseroots.customer.services.api.ConnectedCatalog;
import com.roseroots.customer.services.api.ConnectedRestaurantSettings;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.services.api.RestOrderTracking;
import com.roseroots.customer.services.WaitEstimateService;
import java.time.Clock;
import com.roseroots.customer.utils.Messages;
import java.util.Objects;

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
    private javafx.animation.Timeline brandingPoll;
    private ConnectedRestaurantSettings branding;
    private final java.util.concurrent.ScheduledExecutorService orderExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "customer-order-tracking");
                thread.setDaemon(true);
                return thread;
            });

    @Override public void stop() {
        if (brandingPoll != null) brandingPoll.stop();
        if (branding != null) branding.close();
        orderExecutor.shutdown();
    }

    @Override
    public void start(Stage stage) {
        Font.loadFont(Objects.requireNonNull(getClass().getResource(
                "/fonts/NotoSansEthiopic-Regular.ttf")).toExternalForm(), 16);

        var defaults = new LocalRestaurantSettings();
        var args = getParameters().getRaw();
        boolean demoTable = args.contains("--demo-table");
        boolean demoTakeaway = demoTable || args.contains("--demo-takeaway");
        String origin = System.getenv(BackendAddress.ENVIRONMENT_VARIABLE);
        boolean connected = !demoTakeaway && (args.contains("--connected") || (origin != null && !origin.isBlank()));
        var address = connected ? backendAddress(origin) : null;
        var backend = connected ? orderBackend(address) : null;
        branding = backend == null ? null : new ConnectedRestaurantSettings(defaults, backend, address);
        RestaurantSettings settings = branding == null ? defaults : branding;
        var state = backend == null ? new CustomerState() : new CustomerState(new com.roseroots.customer.state.AssistanceState(
                new com.roseroots.customer.services.api.SpringBootAssistanceService(
                        new com.roseroots.customer.services.api.RestAssistanceClient(backend, orderExecutor)),
                Clock.systemUTC(), Platform::runLater),
                new com.roseroots.customer.state.ReviewState(
                        new com.roseroots.customer.services.api.RestReviewClient(backend), Platform::runLater));
        var menuService = connected
                ? (backend == null ? (com.roseroots.customer.services.MenuService) () -> disconnected() : new ConnectedCatalog(backend, address))
                : new MockMenuService(demoTakeaway);
        var drafts = connected ? null : new LocalDraftSessionService(demoTable
                ? new TerminalDevice("demo-only", null, false) : loadInstallation());
        DiningSessionService sessions = connected
                ? (backend == null ? mode -> disconnected() : new ConnectedTerminal(backend, Clock.systemUTC()))
                : demoTable ? new LocalDiningSessionService(true) : drafts;
        TakeawaySessionService takeaway = demoTakeaway ? new LocalTakeawaySessionService("demo-takeaway-terminal")
                : () -> sessions.openOrResume(ServiceMode.TAKEAWAY);
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
        WaitEstimateService estimates = backend == null ? WaitEstimateService.unavailable() : backend;
        var orders = backend == null ? OrderService.unavailable() : backend;
        var tracking = backend == null ? OrderTrackingService.unavailable() : new RestOrderTracking(backend, orderExecutor);
        var confirmation = new OrderConfirmation(state, orders, Clock.systemUTC(), Platform::runLater);
        Runnable goOrders = () -> navigator.show(Route.ORDERS);
        var payments = backend == null ? null : new com.roseroots.customer.services.api.RestPaymentClient(backend);
        var paymentJournal = backend == null ? null : com.roseroots.customer.state.PaymentCheckout.persistent(
                address.base().toString());
        navigator.register(Route.ORDERS, () -> new OrdersScreen(settings, state, tracking, locale -> {
            state.setLocale(locale);
            goOrders.run();
        }, goHome, goMenu, goCart, chooseCategory, false).withPayments(payments, paymentJournal));
        Runnable goAccepted = () -> navigator.show(Route.ORDER_ACCEPTED);
        navigator.register(Route.ORDER_ACCEPTED, () -> new OrderAcceptedScreen(settings, state, confirmation.accepted(),
                locale -> { state.setLocale(locale); goAccepted.run(); }, goHome, goMenu, goCart, goOrders, chooseCategory));
        Runnable review = () -> navigator.show(Route.ORDER_CONFIRMATION);
        navigator.register(Route.ORDER_CONFIRMATION, () -> new CartScreen(settings, state, estimates, locale -> {
            state.setLocale(locale);
            review.run();
        }, goHome, goMenu, null, chooseCategory, goCart, confirmation, goOrders, goAccepted));
        navigator.register(Route.CART, () -> new CartScreen(settings, state, estimates, locale -> {
            state.setLocale(locale);
            goCart.run();
        }, goHome, goMenu, id -> {
            editing.set(id);
            navigator.show(Route.DISH_DETAILS);
        }, chooseCategory, review, null, goOrders, goAccepted));
        navigator.register(Route.MENU, () -> new MenuScreen(settings, state, menuService, locale -> {
            state.setLocale(locale);
            goMenu.run();
        }, goHome, itemId -> {
            editing.set(null);
            state.menu().selectItem(itemId);
            navigator.show(Route.DISH_DETAILS);
        }, goCart, goOrders));
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
        if (branding != null) {
            branding.changes().addListener(ignored -> {
                stage.setTitle(branding.brand().windowTitle());
                content.setStyle(branding.themeCss());
            });
            branding.refresh();
            brandingPoll = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(15),
                    event -> branding.refresh()));
            brandingPoll.setCycleCount(javafx.animation.Timeline.INDEFINITE);
            brandingPoll.play();
        }
    }

    private static BackendAddress backendAddress(String origin) {
        try {
            return BackendAddress.configured(origin).orElse(null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static SpringBootOrderService orderBackend(BackendAddress address) {
        try {
            String installed = System.getenv("ROSE_ROOTS_INSTALLED_TERMINAL_TOKEN");
            if (installed != null) {
                return address != null && installed.matches("[A-Za-z0-9_-]{43,128}")
                        ? new SpringBootOrderService(address, () -> installed, () -> true) : null;
            }
            String file = System.getenv("ROSE_ROOTS_TERMINAL_TOKEN_FILE");
            if (address != null && file != null && !file.isBlank()) {
                var token = java.nio.file.Files.readString(java.nio.file.Path.of(file)).strip();
                if (token.matches("[A-Za-z0-9._~+/-]+=*")) return new SpringBootOrderService(address, () -> token, () -> true);
            }
        } catch (java.io.IOException | IllegalArgumentException ignored) {
            // Configuration errors are reported without credentials or paths.
        }
        System.getLogger(CustomerApplication.class.getName()).log(System.Logger.Level.WARNING,
                "Connected mode requires a valid HTTPS origin and a provisioned terminal token file.");
        return null;
    }

    private static <T> java.util.concurrent.CompletableFuture<T> disconnected() {
        return java.util.concurrent.CompletableFuture.failedFuture(new OrderService.Problem("SERVICE_UNAVAILABLE", false));
    }

    private static TerminalDevice loadInstallation() {
        try {
            return LocalDeviceConfiguration.load(LocalDeviceConfiguration.defaultPath());
        } catch (java.io.IOException | IllegalArgumentException error) {
            System.getLogger(CustomerApplication.class.getName()).log(System.Logger.Level.WARNING,
                    "Terminal configuration could not be loaded. Using an unassigned local draft.");
            return new TerminalDevice(java.util.UUID.randomUUID().toString(), null, false);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static void openSession(Stage stage, StackPane content, Navigator navigator, CustomerState state,
                                    ServiceMode mode, DiningSessionService dining, TakeawaySessionService takeaway,
                                    boolean discardConfirmed) {
        var messages = new Messages(state.locale());
        content.setDisable(true);
        var request = java.util.concurrent.CompletableFuture.completedFuture(mode)
                .thenCompose(selected -> selected == ServiceMode.TAKEAWAY ? takeaway.openOrResume() : dining.openOrResume(selected));
        request.whenComplete((session, error) -> Platform.runLater(() -> {
            content.setDisable(false);
            if (error != null || session == null || (state.wouldDiscardCart(session) && !session.allowsCart())) {
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
        var cancel = new ButtonType(messages.text("catalog.cancelFilters"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        var ok = new ButtonType(messages.text("navigation.confirm"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, messages.text("session.discardPrompt"), cancel, ok);
        messages.applyDirection(alert.getDialogPane());
        if ("am".equals(messages.language())) alert.getDialogPane().getStyleClass().add("ethiopic");
        alert.getDialogPane().getStyleClass().add("assistance-dialog");
        alert.getDialogPane().getStylesheets().add(CustomerApplication.class.getResource("/styles/theme.css").toExternalForm());
        alert.initOwner(stage);
        alert.setTitle(messages.text("session.switchTitle"));
        alert.setHeaderText(messages.text("session.switchTitle"));
        return alert.showAndWait().orElse(cancel) == ok;
    }
}
