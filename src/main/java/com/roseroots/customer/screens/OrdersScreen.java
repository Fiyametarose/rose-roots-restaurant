package com.roseroots.customer.screens;

import com.roseroots.customer.components.CustomerShell;
import com.roseroots.customer.components.DishCustomizer;
import com.roseroots.customer.components.FoodCard;
import com.roseroots.customer.components.InterfaceIcon;
import com.roseroots.customer.components.MenuPhoto;
import com.roseroots.customer.models.OrderHistory;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.OrderTrackingService;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class OrdersScreen extends javafx.scene.layout.StackPane {
    private enum Filter { ALL, CURRENT, RECENT }

    private final CustomerState customer;
    private final TerminalSession session;
    private final OrderTrackingService service;
    private final Messages messages;
    private final Label notice;
    private final Label heading;
    private final VBox cards = new VBox(10);
    private final VBox summary = new VBox(9);
    private final FlowPane filters = new FlowPane(8, 6);
    private final Button bill;
    private final Button backToOrders;
    private final Timeline freshness;
    private final java.util.Map<UUID, Label> estimates = new java.util.HashMap<>();
    private OrderTrackingService.Watch watch;
    private OrderHistory history;
    private Filter filter = Filter.ALL;
    private boolean showingBill;
    private OrderTrackingService.State connectionState = OrderTrackingService.State.LOADING;
    private int subscription;

    public OrdersScreen(RestaurantSettings settings, CustomerState customer, OrderTrackingService service,
                        Consumer<Locale> language, Runnable home, Runnable menu, Runnable cart,
                        Consumer<String> category, boolean fixture) {
        this.customer = customer;
        this.session = customer.terminalSession();
        this.service = service;
        messages = new Messages(customer.locale());
        var shell = new CustomerShell(settings, customer, messages, language, home, cart);
        shell.displayCategories(customer.menu().catalog(), customer.locale(), category);
        shell.showOrdersAccess(() -> showBill(false), true);

        heading = label("orders.title", "catalog-heading");
        heading.getStyleClass().add("confirmation-heading");
        heading.setId("orders-heading");
        notice = label("orders.loading", "orders-notice");
        notice.setId("orders-connection");
        var group = new ToggleGroup();
        for (var option : Filter.values()) {
            var button = new ToggleButton(messages.text("orders.filter." + option.name().toLowerCase(Locale.ROOT)));
            button.getStyleClass().add("orders-filter");
            button.setId("orders-filter-" + option.name().toLowerCase(Locale.ROOT));
            button.setToggleGroup(group);
            button.setSelected(option == Filter.ALL);
            button.setOnAction(event -> {
                button.setSelected(true);
                filter = option;
                renderList();
            });
            filters.getChildren().add(button);
        }

        var more = CustomerShell.action(messages.text("orders.more"), true, menu);
        more.setId("order-more");
        more.setMaxWidth(Double.MAX_VALUE);
        bill = CustomerShell.action(messages.text("orders.viewBill"), false, () -> showBill(true));
        bill.setId("view-my-bill");
        bill.setMaxWidth(Double.MAX_VALUE);
        backToOrders = CustomerShell.action(messages.text("orders.back"), false, () -> showBill(false));
        backToOrders.setId("bill-back");
        backToOrders.setMaxWidth(Double.MAX_VALUE);
        var billHeading = label("orders.runningBill", "orders-bill-heading");
        billHeading.setGraphic(new InterfaceIcon(InterfaceIcon.Kind.ORDERS));
        var billHeader = new VBox(4, billHeading,
                FoodCard.label(CustomerShell.sessionContext(customer, messages), "orders-bill-context"));
        billHeader.getStyleClass().add("orders-bill-header");
        var review = CustomerShell.action(messages.text("review.action"), false, () ->
                new com.roseroots.customer.components.ReviewDialog(getScene().getWindow(), customer).show());
        review.setId("open-review");
        review.setMaxWidth(Double.MAX_VALUE);
        var billPanel = new VBox(11, billHeader, summary, more, bill, backToOrders, review);
        billPanel.getStyleClass().addAll("dish-information", "orders-bill-panel");
        var listScroll = scroll(cards);
        var billScroll = scroll(billPanel);
        var columns = new HBox(13, listScroll, billScroll);
        listScroll.prefWidthProperty().bind(columns.widthProperty().subtract(13).multiply(.68));
        billScroll.prefWidthProperty().bind(columns.widthProperty().subtract(13).multiply(.32));
        HBox.setHgrow(listScroll, Priority.ALWAYS);
        HBox.setHgrow(billScroll, Priority.ALWAYS);
        var responsive = new javafx.scene.layout.StackPane(columns);
        responsive.setMinHeight(0);
        responsive.widthProperty().addListener((property, previous, width) -> {
            if (width.doubleValue() < 620 && responsive.getChildren().contains(columns)) {
                listScroll.setContent(null);
                billScroll.setContent(null);
                responsive.getChildren().setAll(scroll(new VBox(12, cards, billPanel)));
            } else if (width.doubleValue() >= 620 && !responsive.getChildren().contains(columns)) {
                var old = (ScrollPane) responsive.getChildren().getFirst();
                ((VBox) old.getContent()).getChildren().clear();
                listScroll.setContent(cards);
                billScroll.setContent(billPanel);
                responsive.getChildren().setAll(columns);
            }
        });
        VBox.setVgrow(responsive, Priority.ALWAYS);
        var titleRow = new FlowPane(26, 8, heading, filters);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        var body = new VBox(9, titleRow, notice, responsive);
        if (fixture) body.getChildren().add(0, label("orders.fixture", "customization-warning"));
        body.getStyleClass().add("catalog-content");
        shell.showContent(body, false);
        getChildren().add(shell);

        freshness = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshEstimates()));
        freshness.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((property, previous, current) -> {
            if (current == null) {
                subscription++;
                if (watch != null) watch.close();
                watch = null;
                freshness.stop();
            } else {
                connect();
                freshness.play();
            }
        });
        showBill(false);
    }

    private void connect() {
        if (session == null || !session.canSubmitOrders()) {
            receive(new OrderTrackingService.Update(OrderTrackingService.State.DISCONNECTED, null));
            return;
        }
        int generation = ++subscription;
        try {
            watch = service.watch(Ordering.canonicalId(session.sessionId()), update -> Platform.runLater(() -> {
                if (generation == subscription && getScene() != null && session == customer.terminalSession()) receive(update);
            }));
        } catch (RuntimeException error) {
            receive(new OrderTrackingService.Update(OrderTrackingService.State.ERROR, null));
        }
    }

    private void receive(OrderTrackingService.Update update) {
        var snapshot = update.history();
        if (snapshot != null && (session == null || !snapshot.sessionId().toString().equals(session.sessionId())
                || snapshot.orders().stream().anyMatch(order -> order.mode() != session.mode()))) {
            connectionState = OrderTrackingService.State.ERROR;
            notice.setText(messages.text("orders.error"));
            refreshEstimates();
            return;
        }
        connectionState = update.state();
        var status = switch (connectionState) {
            case LOADING -> "orders.loading";
            case LIVE -> "orders.live";
            case DISCONNECTED -> history == null ? "orders.disconnected" : "orders.stale";
            case RECONNECTING -> "orders.reconnecting";
            case ERROR -> "orders.error";
        };
        notice.setText(messages.text(status));
        if (snapshot != null && !snapshot.equals(history)) {
            history = snapshot;
            renderList();
            renderSummary();
        }
        refreshEstimates();
    }

    private void showBill(boolean value) {
        showingBill = value;
        heading.setText(messages.text(value ? "orders.billTitle" : "orders.title"));
        visibility(filters, !value);
        visibility(backToOrders, value);
        visibility(bill, !value);
        renderList();
        renderSummary();
    }

    private void renderList() {
        cards.getChildren().clear();
        estimates.clear();
        if (history == null) return;
        if (history.orders().isEmpty()) {
            cards.getChildren().add(label("orders.empty", "cart-empty"));
            return;
        }
        if (showingBill) {
            var billed = history.orders().stream().filter(order -> !OrderHistory.billedLines(order).isEmpty()).toList();
            if (billed.isEmpty()) cards.getChildren().add(label("orders.noBillable", "cart-empty"));
            billed.forEach(order -> cards.getChildren().add(billCard(order)));
            return;
        }
        if (filter != Filter.RECENT) renderSection(true);
        if (filter != Filter.CURRENT) renderSection(false);
        refreshEstimates();
    }

    private void renderSection(boolean current) {
        var orders = history.orders().stream().filter(order -> OrderHistory.current(order) == current).toList();
        if (orders.isEmpty()) {
            if (filter != Filter.ALL) cards.getChildren().add(label("orders.filterEmpty", "cart-empty"));
            return;
        }
        var title = FoodCard.label(messages.text(current ? "orders.current" : "orders.recent")
                + " (" + orders.size() + ")", "orders-section");
        cards.getChildren().add(title);
        orders.forEach(order -> cards.getChildren().add(orderCard(order)));
    }

    private VBox orderCard(Ordering.Accepted order) {
        var box = card(order);
        var steps = new FlowPane(6, 5);
        List<Ordering.Status> stages = List.of(Ordering.Status.RECEIVED, Ordering.Status.PREPARING,
                Ordering.Status.READY, order.mode() == ServiceMode.DINE_IN ? Ordering.Status.SERVED : Ordering.Status.COLLECTED);
        if (!stages.contains(order.status())) {
            steps.getChildren().add(status(order.status()));
        } else {
            int index = stages.indexOf(order.status());
            for (int i = 0; i < stages.size(); i++) {
                var label = status(stages.get(i));
                label.getStyleClass().add(i == index ? "orders-stage-active" : i < index ? "orders-stage-done" : "orders-stage-next");
                steps.getChildren().add(label);
            }
        }
        box.getChildren().add(steps);
        if (OrderHistory.current(order)) {
            var eta = new Label();
            eta.getStyleClass().add("orders-estimate");
            visibility(eta, false);
            estimates.put(order.id(), eta);
            box.getChildren().add(eta);
        }
        var items = new VBox(6);
        order.lines().forEach(line -> items.getChildren().add(item(line)));
        var first = order.lines().isEmpty() ? null : customer.menu().catalog().item(order.lines().getFirst().pricing().menuItemId().toString());
        if (first == null) {
            box.getChildren().add(items);
        } else {
            var photo = new MenuPhoto(first.image(), messages.text("catalog.noPhoto"), 105);
            photo.setMinWidth(100);
            photo.setPrefWidth(122);
            photo.setMaxWidth(130);
            var row = new HBox(10, photo, items);
            HBox.setHgrow(items, Priority.ALWAYS);
            box.getChildren().add(row);
        }
        return box;
    }

    private VBox item(Ordering.AcceptedLine line) {
        var price = line.pricing();
        String detail = statusText(line.preparation());
        if (line.billable() && line.review() != Ordering.Review.DECLINED) detail += "  ·  " + money(price.extendedMinor());
        var item = new VBox(3, row(price.name() + "  ×" + price.quantity(), detail, "dish-description"));
        item.getStyleClass().add("orders-item");
        price.options().forEach(option -> item.getChildren().add(FoodCard.label(option.name(), "catalog-muted")));
        if (line.review() != Ordering.Review.NOT_REQUIRED) {
            String key = "orders.review." + line.review().name().toLowerCase(Locale.ROOT);
            var reviewStyle = switch (line.review()) {
                case APPROVED_FOR_PREPARATION -> "orders-review-approved";
                case DECLINED -> "orders-review-declined";
                default -> "orders-review";
            };
            item.getChildren().add(label(key, reviewStyle));
        }
        if (line.allergy().disclosed()) {
            String disclosure = java.util.stream.Stream.concat(line.allergy().allergens().stream().sorted()
                    .map(allergen -> DishCustomizer.allergenLabel(messages, allergen)),
                    java.util.stream.Stream.of(line.allergy().other()).filter(value -> !value.isBlank()))
                    .collect(java.util.stream.Collectors.joining(", "));
            item.getChildren().add(FoodCard.label(messages.text("custom.itemAllergies") + ": " + disclosure, "catalog-muted"));
        }
        if (!line.reviewMessage().isBlank()) item.getChildren().add(FoodCard.label(line.reviewMessage(), "dish-description"));
        return item;
    }

    private VBox billCard(Ordering.Accepted order) {
        var box = card(order);
        for (var line : OrderHistory.billedLines(order)) {
            var price = line.pricing();
            box.getChildren().add(row(price.name() + "  ×" + price.quantity(), money(price.extendedMinor()), "dish-description"));
            box.getChildren().add(row(messages.text("cart.baseUnit"), money(price.baseUnitMinor()), "catalog-muted"));
            price.options().forEach(option -> box.getChildren().add(row(option.name(),
                    money(option.unitAdjustmentMinor()), "catalog-muted")));
        }
        var totals = order.totals();
        if (totals.serviceMinor() > 0) box.getChildren().add(row(messages.text("confirm.serviceCharge"), money(totals.serviceMinor()), "catalog-muted"));
        if (totals.taxMinor() > 0) box.getChildren().add(row(messages.text("confirm.tax"), money(totals.taxMinor()), "catalog-muted"));
        box.getChildren().add(row(messages.text("orders.orderTotal"), money(totals.totalMinor()), "dish-section-heading"));
        return box;
    }

    private VBox card(Ordering.Accepted order) {
        var number = FoodCard.label(number(order), "orders-card-heading");
        number.setTooltip(new Tooltip(order.id().toString()));
        number.setAccessibleText(messages.text("orders.number") + " " + order.id());
        var time = FoodCard.label(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(customer.locale())
                .withZone(ZoneId.systemDefault()).format(order.acceptedAt()), "catalog-muted");
        var box = new VBox(7, new FlowPane(10, 3, number, time));
        box.getStyleClass().addAll("dish-information", "orders-card");
        return box;
    }

    private void renderSummary() {
        summary.getChildren().clear();
        bill.setDisable(history == null || history.orders().isEmpty());
        if (history == null) {
            summary.getChildren().add(label("orders.billUnavailable", "catalog-muted"));
            return;
        }
        if (!showingBill) {
            history.orders().stream().filter(order -> !OrderHistory.billedLines(order).isEmpty())
                    .forEach(order -> summary.getChildren().add(row(number(order),
                            money(history.orderTotals().getOrDefault(order.id(), order.totals()).totalMinor()), "dish-description")));
        }
        var totals = history.totals();
        summary.getChildren().add(row(messages.text("cart.subtotal"), money(totals.subtotalMinor()), "dish-description"));
        if (totals.serviceMinor() != 0) summary.getChildren().add(row(messages.text("confirm.serviceCharge"), money(totals.serviceMinor()), "dish-description"));
        if (totals.taxMinor() != 0) summary.getChildren().add(row(messages.text("confirm.tax"), money(totals.taxMinor()), "dish-description"));
        var total = row(messages.text("orders.total"), money(totals.totalMinor()), "cart-line-heading");
        total.getStyleClass().add("cart-final-total");
        total.setId("combined-bill-total");
        summary.getChildren().add(total);
    }

    private void refreshEstimates() {
        if (history == null) return;
        var now = Instant.now();
        estimates.forEach((id, label) -> {
            var estimate = connectionState == OrderTrackingService.State.LIVE
                    ? history.remaining(id, now) : java.util.Optional.<com.roseroots.customer.models.WaitEstimate>empty();
            visibility(label, estimate.isPresent());
            estimate.ifPresent(value -> label.setText(messages.text("orders.estimate") + " "
                    + MenuText.waitRange(value, customer.locale(), messages)));
        });
    }

    private Label status(Ordering.Status status) {
        return FoodCard.label(statusText(status), "orders-stage");
    }

    private String statusText(Ordering.Status status) {
        return messages.text("orders.status." + status.name().toLowerCase(Locale.ROOT));
    }

    private String number(Ordering.Accepted order) {
        return messages.text("orders.number") + " #" + order.id().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String money(long minor) {
        return MenuText.price(BigDecimal.valueOf(minor, 2), Currency.getInstance("KES"), customer.locale());
    }

    private Label label(String key, String style) {
        return FoodCard.label(messages.text(key), style);
    }

    private static HBox row(String key, String value, String style) {
        var start = FoodCard.label(key, style);
        start.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(start, Priority.ALWAYS);
        var end = FoodCard.label(value, style);
        end.setMinWidth(Region.USE_PREF_SIZE);
        return new HBox(9, start, end);
    }

    private static ScrollPane scroll(Region content) {
        var pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setMinSize(0, 0);
        pane.getStyleClass().add("catalog-scroll");
        return pane;
    }

    private static void visibility(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
