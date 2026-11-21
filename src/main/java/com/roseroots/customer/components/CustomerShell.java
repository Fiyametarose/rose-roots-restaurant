package com.roseroots.customer.components;

import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;

public final class CustomerShell extends BorderPane {
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    private final VBox categories = new VBox();
    private final Button ordersAccess = new Button();
    private final CustomerState state;
    private final CartSummaryBar cartSummary;
    private final Button cart;
    private final Messages messages;

    public CustomerShell(RestaurantSettings settings, CustomerState state, Messages messages,
                         Consumer<Locale> chooseLanguage, Runnable goHome, Runnable goCart) {
        this.state = state;
        this.messages = messages;
        cartSummary = new CartSummaryBar(state, settings, messages, goCart);
        getStyleClass().add("catalog-shell");
        if (messages.language().equals("am")) {
            getStyleClass().add("ethiopic");
        }

        var brand = new BrandHeader(settings.brand());
        var home = new Button(messages.text("catalog.backToWelcome"));
        home.setId("back-to-welcome");
        home.setWrapText(true);
        home.setGraphic(new InterfaceIcon(InterfaceIcon.Kind.HOME));
        home.setOnAction(event -> goHome.run());
        home.getStyleClass().add("catalog-home");
        ordersAccess.setId("my-orders");
        ordersAccess.setText(messages.text("orders.title"));
        ordersAccess.setGraphic(new InterfaceIcon(InterfaceIcon.Kind.ORDERS));
        ordersAccess.setWrapText(true);
        ordersAccess.setMaxWidth(Double.MAX_VALUE);
        ordersAccess.getStyleClass().add("catalog-category");
        ordersAccess.setVisible(false);
        ordersAccess.managedProperty().bind(ordersAccess.visibleProperty());
        var railContents = new VBox(27, brand, new VBox(8, categories, ordersAccess));
        railContents.setAlignment(Pos.TOP_CENTER);
        railContents.getStyleClass().add("catalog-rail-content");
        categories.getStyleClass().add("catalog-categories");

        var decoration = new ImageView(new Image(CustomerShell.class
                .getResource("/images/botanical-lower.png").toExternalForm()));
        decoration.setPreserveRatio(true);
        var art = decoration.getImage();
        decoration.setViewport(new Rectangle2D(0, 4, art.getWidth(), art.getHeight() - 4));
        decoration.setFitWidth(163);
        decoration.setOpacity(.70);
        decoration.setScaleX(-1);
        decoration.setMouseTransparent(true);
        decoration.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(720));
        decoration.managedProperty().bind(decoration.visibleProperty());
        StackPane.setAlignment(decoration, Pos.BOTTOM_LEFT);
        StackPane.setMargin(decoration, new Insets(0, 0, 70, 8));
        var upper = new ImageView(new Image(CustomerShell.class
                .getResource("/images/botanical-upper.png").toExternalForm()));
        upper.setPreserveRatio(true);
        upper.setFitWidth(90);
        upper.setScaleX(-1);
        upper.setOpacity(.42);
        upper.setMouseTransparent(true);
        upper.visibleProperty().bind(decoration.visibleProperty());
        upper.managedProperty().bind(upper.visibleProperty());
        StackPane.setAlignment(upper, Pos.TOP_LEFT);
        var railScroll = new ScrollPane(railContents);
        railScroll.setFitToWidth(true);
        railScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var railSurface = railShape("catalog-rail-surface");
        StackPane.setMargin(railSurface, new Insets(0, 4, 0, 0));
        var railNavigation = new BorderPane(railScroll);
        railNavigation.setBottom(home);
        BorderPane.setAlignment(home, Pos.CENTER_LEFT);
        home.setMaxWidth(Double.MAX_VALUE);
        home.getStyleClass().add("catalog-home-bottom");
        BorderPane.setMargin(home, new Insets(8, 27, 19, 12));
        var rail = new StackPane(railShape("catalog-rail-edge"), railSurface, decoration, upper, railNavigation);
        rail.getStyleClass().add("catalog-rail");
        rail.prefWidthProperty().bind(Bindings.when(widthProperty().lessThan(720)).then(112).otherwise(221));
        setLeft(rail);

        var context = new Label(sessionContext(state, messages));
        context.setId("session-context");
        context.getStyleClass().add("catalog-context");
        context.setWrapText(true);
        var cuisines = new Label(String.join("  ·  ",
                messages.text("catalog.cuisine.ethiopian"), messages.text("catalog.cuisine.south_sudanese"),
                messages.text("catalog.cuisine.international")));
        cuisines.getStyleClass().add("header-cuisine");
        cuisines.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(1190));
        cuisines.managedProperty().bind(cuisines.visibleProperty());
        var identity = new HBox(13, context, cuisines);
        identity.setAlignment(Pos.CENTER_LEFT);
        var locale = new ThemedComboBox<Locale>();
        locale.getItems().setAll(settings.languages());
        locale.setAccessibleText(messages.text("welcome.language"));
        locale.setId("catalog-language");
        locale.getStyleClass().add("catalog-language");
        locale.setCellFactory(list -> languageCell());
        locale.setButtonCell(languageCell());
        locale.setValue(state.locale());
        locale.setOnAction(event -> {
            if (locale.getValue() != null) {
                chooseLanguage.accept(locale.getValue());
            }
        });

        var assistance = action(messages.text("catalog.assistance"), false, () ->
                new AssistanceDialog(getScene().getWindow(), state, false).show());
        assistance.setId("header-assistance");
        assistance.setGraphic(new InterfaceIcon(InterfaceIcon.Kind.MAIN));
        assistance.getStyleClass().add("catalog-assistance");
        cart = action("", false, () -> {
            if (goCart != null) {
                goCart.run();
            }
        });
        cart.setId("header-cart");
        cart.setGraphic(new InterfaceIcon(InterfaceIcon.Kind.CART));
        cart.getStyleClass().add("catalog-cart");
        refreshCart();
        var tools = new FlowPane(8, 5, locale, assistance, cart);
        tools.setAlignment(Pos.CENTER_RIGHT);
        var header = new BorderPane(tools);
        header.getStyleClass().add("catalog-header");
        header.setLeft(identity);
        BorderPane.setAlignment(identity, Pos.CENTER_LEFT);
        BorderPane.setMargin(identity, new Insets(0, 12, 0, 0));
        widthProperty().addListener((property, previous, width) -> {
            boolean compact = width.doubleValue() < 875;
            pseudoClassStateChanged(COMPACT, width.doubleValue() < 720);
            if (compact && header.getLeft() != null) {
                header.setLeft(null);
                tools.getChildren().addFirst(identity);
                tools.setAlignment(Pos.CENTER_LEFT);
            } else if (!compact && header.getLeft() == null) {
                tools.getChildren().remove(identity);
                header.setLeft(identity);
                tools.setAlignment(Pos.CENTER_RIGHT);
            }
        });
        top = header;
    }

    private final Node top;

    public void showContent(Node content) {
        showContent(content, true);
    }

    public void showContent(Node content, boolean showCartSummary) {
        var area = new BorderPane(content, top, null, null, null);
        var footer = new VBox(6);
        footer.getStyleClass().add("catalog-footer");
        var session = state.terminalSession();
        if (session != null && session.status() == com.roseroots.customer.models.TerminalSession.Status.UNASSIGNED) {
            var notice = new Label(messages.text("session.staffConfiguration"));
            notice.setWrapText(true);
            notice.setId("session-status");
            notice.getStyleClass().add("session-notice");
            footer.getChildren().add(notice);
        }
        if (showCartSummary) footer.getChildren().add(cartSummary);
        if (!footer.getChildren().isEmpty()) area.setBottom(footer);
        setCenter(area);
    }

    public void refreshCart() {
        cart.setText(messages.text("catalog.cart") + "  ·  " +
                java.text.NumberFormat.getIntegerInstance(state.locale()).format(state.cart().count()));
        cartSummary.refresh();
    }

    public void showOrdersAccess(Runnable action, boolean selected) {
        ordersAccess.setVisible(true);
        ordersAccess.setOnAction(event -> action.run());
        if (selected) {
            ordersAccess.getStyleClass().add("category-active");
            categories.getChildren().forEach(node -> node.getStyleClass().remove("category-active"));
        }
    }

    public void displayCategories(MenuCatalog catalog, Locale locale, Consumer<String> onCategory) {
        categories.getChildren().clear();
        for (var category : catalog.childrenOf(null)) {
            var button = new Button(category.name().in(locale));
            button.setId("category-" + category.id());
            button.setWrapText(true);
            button.setGraphic(new InterfaceIcon(categoryIcon(category.id())));
            button.setMaxWidth(Double.MAX_VALUE);
            button.getStyleClass().add("catalog-category");
            if (category.id().equals(state.menu().categoryId())) {
                button.getStyleClass().add("category-active");
            }
            button.setOnAction(event -> onCategory.accept(category.id()));
            categories.getChildren().add(button);
        }
    }

    private static Region railShape(String style) {
        var surface = new Region();
        surface.setShape(new Path(new MoveTo(0, 0), new LineTo(215, 0),
                new CubicCurveTo(177, 118, 192, 296, 197, 420),
                new CubicCurveTo(202, 548, 190, 686, 177, 800),
                new LineTo(0, 800), new ClosePath()));
        surface.getStyleClass().add(style);
        surface.setMouseTransparent(true);
        return surface;
    }

    private static InterfaceIcon.Kind categoryIcon(String id) {
        return switch (id) {
            case "starters" -> InterfaceIcon.Kind.STARTER;
            case "desserts" -> InterfaceIcon.Kind.DESSERT;
            case "drinks" -> InterfaceIcon.Kind.DRINK;
            default -> InterfaceIcon.Kind.MAIN;
        };
    }

    public static Button action(String text, boolean primary, Runnable onAction) {
        var button = new Button(text);
        button.getStyleClass().addAll("catalog-action", primary ? "action-primary" : "action-secondary");
        button.setOnAction(event -> onAction.run());
        return button;
    }

    private static ListCell<Locale> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Locale option, boolean empty) {
                super.updateItem(option, empty);
                getStyleClass().remove("ethiopic-choice");
                setText(empty || option == null ? null : MenuText.languageName(option));
                if (!empty && option != null && option.getLanguage().equals("am")) {
                    getStyleClass().add("ethiopic-choice");
                }
            }
        };
    }

    public static String sessionContext(CustomerState state, Messages messages) {
        var session = state.terminalSession();
        if (session != null && session.status() == com.roseroots.customer.models.TerminalSession.Status.LOCAL_DRAFT) {
            String label = session.device().table() == null
                    ? messages.text(state.serviceMode() == ServiceMode.DINE_IN
                            ? "service.dineIn" : "service.takeaway")
                    : messages.text("session.table") + " " + session.device().table().displayNumber();
            return label + "  ·  " + messages.text("session.localDraft");
        }
        if (state.serviceMode() == ServiceMode.DINE_IN) {
            if (session == null || session.device().table() == null) {
                return messages.text("service.dineIn");
            }
            return messages.text("session.table") + " " + session.device().table().displayNumber()
                    + (session.device().demo() ? "  ·  " + messages.text("session.demo") : "");
        }
        return session != null && session.pickupReference() != null
                ? messages.text("catalog.pickup") + " " + session.pickupReference()
                    + (session.device().demo() ? "  ·  " + messages.text("session.demo") : "")
                : messages.text("service.takeaway");
    }
}
