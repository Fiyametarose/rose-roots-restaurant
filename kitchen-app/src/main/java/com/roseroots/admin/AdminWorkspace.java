package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class AdminWorkspace {
    private final Stage window = new Stage();
    private final BorderPane root = new BorderPane();
    private final VBox page = new VBox(18);
    private final Label status = label("", "muted");
    private final Button refresh = new Button("Refresh");
    private final Timeline polling = new Timeline(new KeyFrame(Duration.seconds(30), event -> poll()));
    private AdminApi session;
    private AdminSection section;
    private boolean loading;
    private final java.util.Map<AdminSection, ToggleButton> destinationsBySection =
            new java.util.EnumMap<>(AdminSection.class);
    private long lastInteraction;

    public AdminWorkspace(Stage kitchen) {
        window.initOwner(kitchen);
        window.initModality(Modality.WINDOW_MODAL);
        window.setTitle("Rose & Roots | Admin Panel");
        window.setMinWidth(760);
        window.setMinHeight(550);
        Scene scene = new Scene(root, 1260, 780);
        scene.getStylesheets().add(getClass().getResource("/admin/admin.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> recordInteraction());
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> recordInteraction());
        scene.addEventFilter(ScrollEvent.SCROLL, event -> recordInteraction());
        window.setScene(scene);
        polling.setCycleCount(Timeline.INDEFINITE);
        window.setOnCloseRequest(event -> {
            event.consume();
            close();
        });
        window.setOnHidden(event -> terminateSession());
        loginScreen("");
        window.show();
    }

    private void loginScreen(String message) {
        root.setLeft(null);
        root.setTop(null);
        TextField username = new TextField();
        username.setPromptText("Manager username");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        Label usernameLabel = label("Username", "field-label");
        usernameLabel.setLabelFor(username);
        Label passwordLabel = label("Password", "field-label");
        passwordLabel.setLabelFor(password);
        Label feedback = label(message, "feedback");
        Button signIn = new Button("Sign in");
        signIn.setDefaultButton(true);
        Button back = new Button("Back to Kitchen");
        back.getStyleClass().add("secondary");
        back.setOnAction(event -> close());
        HBox identity = new HBox(10, rose(38), label("Rose & Roots", "login-brand"));
        identity.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, identity, label("Manager sign-in", "heading"),
                label("Sign in with your manager account.", "muted"), usernameLabel, username,
                passwordLabel, password, new HBox(10, signIn, back), feedback);
        box.getStyleClass().add("login-card");
        box.setMaxWidth(430);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        feedback.setWrapText(true);
        signIn.setOnAction(event -> {
            String name = username.getText().trim();
            String secret = password.getText();
            if (name.isEmpty() || secret.isEmpty()) {
                feedback.setText("Enter your username and password.");
                return;
            }
            AdminApi attempt;
            try {
                attempt = new AdminApi(AdminApi.configuredAddress(
                        System.getenv("ROSE_ADMIN_SERVER_URL"), System.getenv("ROSE_BACKEND_URL")));
            } catch (IllegalArgumentException error) {
                feedback.setText("The Admin server address is invalid. Use HTTPS for a remote server.");
                return;
            }
            password.clear();
            box.setDisable(true);
            feedback.setText("Signing in…");
            background(() -> {
                attempt.login(name, secret);
                return attempt;
            }, (authenticated, failure) -> {
                if (!window.isShowing()) {
                    if (authenticated != null) {
                        release(authenticated);
                    }
                    return;
                }
                if (failure != null) {
                    box.setDisable(false);
                    feedback.setText(loginFailure(failure));
                    password.requestFocus();
                    return;
                }
                session = authenticated;
                recordInteraction();
                openWorkspace();
            });
        });
        root.setCenter(new StackPane(box));
        Platform.runLater(username::requestFocus);
    }

    private void openWorkspace() {
        Button signOut = new Button("Sign out");
        signOut.setOnAction(event -> signOut());
        HBox header = new HBox(14, label("Admin Panel", "header-title"), spacer(),
                label("Manager access", "muted"), signOut);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("admin-header");
        root.setTop(header);

        VBox identity = new VBox(5, rose(44), label("Rose & Roots", "brand"),
                label("RESTAURANT", "brand-caption"));
        identity.setAlignment(Pos.CENTER);
        VBox sidebar = new VBox(5, identity);
        sidebar.getStyleClass().add("admin-sidebar");
        ToggleGroup destinations = new ToggleGroup();
        destinationsBySection.clear();
        for (AdminSection destination : AdminSection.values()) {
            ToggleButton button = new ToggleButton(destination.label);
            button.getStyleClass().add("destination");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setToggleGroup(destinations);
            button.setSelected(destination == AdminSection.DASHBOARD);
            button.setOnAction(event -> {
                button.setSelected(true);
                navigate(destination);
            });
            sidebar.getChildren().add(button);
            destinationsBySection.put(destination, button);
        }
        ScrollPane navigation = scroll(sidebar);
        navigation.getStyleClass().add("navigation");
        navigation.setPrefWidth(220);
        root.setLeft(navigation);
        page.getStyleClass().add("admin-page");
        ScrollPane workspaceScroll = scroll(page);
        workspaceScroll.getStyleClass().add("admin-workspace-scroll");
        root.setCenter(workspaceScroll);
        refresh.setOnAction(event -> load(true));
        navigate(AdminSection.DASHBOARD);
        polling.play();
    }

    private void navigate(AdminSection destination) {
        navigate(destination, null);
    }

    private void navigate(AdminSection destination, JsonObject dish) {
        section = destination;
        destinationsBySection.get(destination).setSelected(true);
        boolean catalog = destination == AdminSection.MENU || destination == AdminSection.CATEGORIES
                || destination == AdminSection.DISH || destination == AdminSection.PRICES;
        if (root.getCenter() instanceof ScrollPane scroll) {
            scroll.setFitToHeight(!catalog && destination != AdminSection.ORDERS
                    && destination != AdminSection.REVIEWS && destination != AdminSection.BRANDING);
            scroll.setVvalue(0);
        }
        if (destination == AdminSection.DASHBOARD) {
            render(null, "Loading restaurant overview…", true);
            load(true);
        } else if (catalog) {
            AdminApi active = session;
            page.getChildren().setAll(new CatalogWorkspace(active, destination, dish,
                    item -> navigate(AdminSection.DISH, item), () -> navigate(AdminSection.MENU),
                    () -> expired(active)));
        } else if (destination == AdminSection.ORDERS) {
            AdminApi active = session;
            var history = new OperationsWorkspace(active, () -> expired(active),
                    () -> ((ScrollPane) root.getCenter()).setVvalue(0));
            var operations = new javafx.scene.control.TabPane(new javafx.scene.control.Tab("Orders and history", history),
                    new javafx.scene.control.Tab("Cash confirmation", new CashPaymentsWorkspace(active, () -> expired(active))));
            operations.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
            page.getChildren().setAll(operations);
        } else if (destination == AdminSection.REVIEWS) {
            AdminApi active = session;
            page.getChildren().setAll(new ReviewsWorkspace(active, () -> expired(active),
                    () -> ((ScrollPane) root.getCenter()).setVvalue(0)));
        } else if (destination == AdminSection.STAFF) {
            AdminApi active = session;
            page.getChildren().setAll(new StaffWorkspace(active, () -> expired(active)));
        } else if (destination == AdminSection.KITCHEN) {
            AdminApi active = session;
            page.getChildren().setAll(new KitchenConfigurationWorkspace(active, () -> expired(active)));
        } else if (destination == AdminSection.TABLES) {
            AdminApi active = session;
            page.getChildren().setAll(new VisitsWorkspace(active, () -> expired(active)));
        } else if (destination == AdminSection.BRANDING) {
            AdminApi active = session;
            page.getChildren().setAll(new BrandingWorkspace(active, () -> expired(active)));
        } else {
            page.getChildren().setAll(label(destination.heading, "heading"),
                    panel("Planned section", label("This section will be available in a later phase.", "muted")));
        }
    }

    private void load(boolean foreground) {
        if (section != AdminSection.DASHBOARD || session == null || loading) {
            return;
        }
        AdminApi active = session;
        loading = true;
        refresh.setDisable(true);
        if (foreground) {
            status.setText("Loading restaurant overview…");
        }
        background(active::dashboard, (data, failure) -> {
            if (session != active) {
                return;
            }
            loading = false;
            refresh.setDisable(false);
            if (failure instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                expired(active);
                return;
            }
            if (section != AdminSection.DASHBOARD) {
                return;
            }
            if (failure != null) {
                boolean disconnected = failure instanceof IOException && !(failure instanceof AdminApi.Rejected);
                render(null, disconnected ? "Disconnected — the server could not be reached."
                        : "The server could not load the overview.", false);
                return;
            }
            render(data, hasData(data) ? "Connected" : "Connected — operational data sources unavailable.", false);
        });
    }

    private void expired(AdminApi active) {
        if (session == active) {
            clearSession();
            release(active);
            loginScreen("Your manager session ended. Sign in again.");
        }
    }

    private void recordInteraction() {
        lastInteraction = System.nanoTime();
    }

    private void poll() {
        if (System.nanoTime() - lastInteraction >= java.time.Duration.ofMinutes(15).toNanos()) {
            signOut();
        } else {
            load(false);
        }
    }

    private void render(JsonObject data, String message, boolean initialLoad) {
        status.setText(message);
        HBox heading = new HBox(12, label("Restaurant Overview", "heading"), spacer(), refresh);
        heading.setAlignment(Pos.CENTER_LEFT);
        if (initialLoad) {
            ProgressIndicator progress = new ProgressIndicator();
            progress.setMaxSize(28, 28);
            page.getChildren().setAll(heading, status, progress);
            return;
        }
        FlowPane cards = new FlowPane(12, 12,
                metric("Today’s Orders", value(data, "ordersToday")),
                metric("Active Dining Sessions", value(data, "activeDiningSessions")),
                metric("Today’s Sales", value(data, "salesToday")),
                metric("Average Review Rating", value(data, "averageRating")));

        VBox orders = panel("Recent Orders (Today)", recentOrders(list(data, "recentOrders")));
        VBox secondary = new VBox(14,
                panel("Sold-Out Items", listContent(list(data, "soldOutItems"), "No sold-out items.")),
                panel("Service Alerts", listContent(list(data, "serviceAlerts"), "No active service alerts.")));
        GridPane operations = new GridPane();
        operations.setHgap(14);
        operations.setVgap(14);
        operations.add(orders, 0, 0);
        operations.add(secondary, 0, 1);
        GridPane.setHgrow(orders, Priority.ALWAYS);
        GridPane.setValignment(orders, javafx.geometry.VPos.TOP);
        operations.widthProperty().addListener((observable, before, after) -> {
            double width = after.doubleValue();
            boolean wide = width >= 820;
            GridPane.setConstraints(secondary, wide ? 1 : 0, wide ? 0 : 1);
            secondary.setPrefWidth(wide ? width * 0.31 : width);
        });
        page.getChildren().setAll(heading, status, cards, operations);
    }

    private static Node recentOrders(JsonArray orders) {
        TableView<JsonObject> table = new TableView<>();
        table.setPlaceholder(label(orders == null ? "Order data is unavailable." : "No orders today.", "muted"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().setAll(java.util.List.of(
                column("Time", "time"), column("Order #", "number"), column("Table / Type", "tableOrType"),
                column("Items", "items"), column("Amount", "amount"), column("Status", "status")));
        if (orders != null) {
            for (JsonElement item : orders) {
                if (item.isJsonObject()) {
                    table.getItems().add(item.getAsJsonObject());
                }
            }
        }
        table.setPrefHeight(Math.clamp(35 + 42 * table.getItems().size(), 180, 330));
        return table;
    }

    private static TableColumn<JsonObject, String> column(String heading, String key) {
        TableColumn<JsonObject, String> column = new TableColumn<>(heading);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(value(row.getValue(), key)));
        if (key.equals("items")) {
            column.setPrefWidth(170);
        } else if (key.equals("tableOrType")) {
            column.setMinWidth(93);
        }
        return column;
    }

    private static VBox listContent(JsonArray entries, String empty) {
        VBox content = new VBox(8);
        content.getStyleClass().add("panel-message");
        if (entries == null || entries.isEmpty()) {
            content.getChildren().add(label(entries == null ? "Data unavailable." : empty, "muted"));
        } else {
            for (JsonElement entry : entries) {
                if (entry.isJsonPrimitive()) {
                    Label item = label(entry.getAsString(), "list-item");
                    item.setWrapText(true);
                    content.getChildren().add(item);
                }
            }
        }
        return content;
    }

    private static VBox metric(String title, String value) {
        VBox card = new VBox(8, label(title, "field-label"), label(value, "metric-value"));
        card.getStyleClass().add("metric");
        card.setPrefWidth(208);
        return card;
    }

    private static VBox panel(String title, Node content) {
        Label header = label(title, "panel-title");
        header.setMaxWidth(Double.MAX_VALUE);
        VBox panel = new VBox(header, content);
        panel.getStyleClass().add("panel");
        return panel;
    }

    private static JsonArray list(JsonObject data, String key) {
        return data != null && data.has(key) && data.get(key).isJsonArray() ? data.getAsJsonArray(key) : null;
    }

    private static String value(JsonObject data, String key) {
        return data != null && data.has(key) && data.get(key).isJsonPrimitive()
                ? data.get(key).getAsString() : "Unavailable";
    }

    private static boolean hasData(JsonObject data) {
        return data.entrySet().stream().anyMatch(entry -> !entry.getValue().isJsonNull());
    }

    private void signOut() {
        AdminApi active = clearSession();
        root.setLeft(null);
        root.setTop(null);
        root.setCenter(new StackPane(label("Signing out…", "heading")));
        background(() -> {
            active.logout();
            return null;
        }, (ignored, failure) -> {
            if (window.isShowing()) {
                loginScreen(failure == null ? "Signed out."
                        : "Signed out on this device. The server could not confirm sign-out.");
            }
        });
    }

    private AdminApi clearSession() {
        polling.stop();
        AdminApi active = session;
        session = null;
        section = null;
        loading = false;
        page.getChildren().clear();
        return active;
    }

    private void close() {
        window.hide();
    }

    private void terminateSession() {
        AdminApi active = clearSession();
        if (active != null) {
            release(active);
        }
    }

    private static void release(AdminApi active) {
        background(() -> {
            active.logout();
            return null;
        }, (ignored, failure) -> {});
    }

    private static String loginFailure(Exception failure) {
        if (failure instanceof AdminApi.Rejected rejected) {
            return switch (rejected.status) {
                case 401 -> "The username or password was incorrect.";
                case 403 -> "Manager access was denied.";
                default -> "The server could not sign you in. Try again.";
            };
        }
        return "Could not reach the Admin server. Check the connection and try again.";
    }

    static <T> void background(Callable<T> work, BiConsumer<T, Exception> result) {
        CompletableFuture.runAsync(() -> {
            try {
                T value = work.call();
                Platform.runLater(() -> result.accept(value, null));
            } catch (Exception failure) {
                Platform.runLater(() -> result.accept(null, failure));
            }
        });
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        return scroll;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private ImageView rose(int size) {
        return new ImageView(new Image(getClass().getResourceAsStream("/admin/rose-mark.png"),
                size, size, true, true));
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
