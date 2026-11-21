package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import static com.roseroots.admin.CatalogControls.*;
import static com.roseroots.admin.OperationsPresentation.*;

final class OperationsWorkspace extends VBox {
    private static final String UNAVAILABLE_MESSAGE = "Orders and payments are unavailable until the shared services are connected.";
    private final AdminApi api;
    private final Runnable expired;
    private final Runnable scrollToTop;
    private final VBox screen = new VBox(14);
    private final VBox list = new VBox(14);
    private final FlowPane metrics = new FlowPane(10, 8);
    private final Label status = label("", "muted");
    private final TextField search = new TextField();
    private final DatePicker from = datePicker();
    private final DatePicker to = datePicker();
    private final ComboBox<Choice> orderStatus = choices("All order statuses", "RECEIVED", "PREPARING", "READY",
            "SERVED", "COLLECTED", "CANCELLED");
    private final ComboBox<Choice> paymentStatus = choices("All payment statuses", "UNPAID", "PENDING", "SUCCESSFUL",
            "FAILED", "CANCELLED", "REFUNDED");
    private final ComboBox<Choice> serviceType = choices("All service types", "DINE_IN", "TAKEAWAY");
    private final ComboBox<Choice> paymentMethod = choices("All methods");
    private final ComboBox<Choice> sort = new ComboBox<>();
    private final TableView<JsonObject> orders = table();
    private final Button previous = button("Previous", this::previous);
    private final Button next = button("Next", this::next);
    private final Label pageLabel = label("", "muted");
    private final ArrayList<String> cursors = new ArrayList<>();
    private Filters filters;
    private int pageIndex;
    private String nextCursor;
    private String currency = "";
    private long revision;

    OperationsWorkspace(AdminApi api, Runnable expired, Runnable scrollToTop) {
        super(12);
        this.api = api;
        this.expired = expired;
        this.scrollToTop = scrollToTop;
        getChildren().addAll(label("Orders & Payments", "heading"),
                label("Monitor table and pickup orders, historical bills, and read-only payment activity.", "muted"), screen);
        screen.getChildren().setAll(list);
        buildList();
        applyFilters();
    }

    private void buildList() {
        search.setPromptText("Order number, table, pickup or item");
        search.setPrefColumnCount(25);
        search.setOnAction(event -> applyFilters());
        sort.getItems().addAll(new Choice("Newest first", "NEWEST"), new Choice("Oldest first", "OLDEST"),
                new Choice("Highest total", "TOTAL_DESC"), new Choice("Lowest total", "TOTAL_ASC"),
                new Choice("Order number", "ORDER_NUMBER"));
        sort.getSelectionModel().selectFirst();

        var filtersRow = new FlowPane(10, 10, field("Search orders", search), field("From", from), field("Through", to),
                field("Order status", orderStatus), field("Payment status", paymentStatus),
                field("Service type", serviceType), field("Payment method", paymentMethod), field("Sort by", sort));

        var clear = button("Clear filters", () -> {
            search.clear();
            from.setValue(null);
            to.setValue(null);
            for (var combo : List.of(orderStatus, paymentStatus, serviceType, paymentMethod, sort)) {
                combo.getSelectionModel().selectFirst();
            }
            applyFilters();
        });
        clear.getStyleClass().add("secondary");
        var apply = button("Apply filters", this::applyFilters);
        var refresh = button("Refresh", this::applyFilters);
        refresh.getStyleClass().add("secondary");

        orders.getColumns().setAll(List.of(column("Order", 92, row -> value(row, "orderNumber")),
                column("Submitted", 163, row -> timestamp(row, "submittedAt")),
                column("Table / pickup", 119, row -> value(row, "serviceReference")),
                column("Type", 80, row -> state(text(row, "serviceType"))),
                column("Items", 55, row -> value(row, "itemCount")),
                column("Total", 100, row -> amount(row, "totalMinor", currency)),
                column("Order status", 112, row -> state(text(row, "orderStatus"))),
                column("Payment", 104, row -> state(text(row, "paymentStatus")))));
        orders.getColumns().forEach(column -> {
            column.setSortable(false);
            column.setMinWidth(Math.min(column.getPrefWidth(), 72));
        });
        orders.widthProperty().addListener((observable, before, width) -> {
            orders.setColumnResizePolicy(width.doubleValue() < 790
                    ? TableView.UNCONSTRAINED_RESIZE_POLICY : TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        });
        orders.setPrefHeight(350);
        var details = button("View details", () -> openOrder(orders.getSelectionModel().getSelectedItem()));
        details.disableProperty().bind(orders.getSelectionModel().selectedItemProperty().isNull());
        orders.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && orders.getSelectionModel().getSelectedItem() != null) {
                details.fire();
            }
        });
        var paging = new FlowPane(8, 8, previous, next, pageLabel, details);
        paging.setAlignment(Pos.CENTER_LEFT);
        list.getChildren().setAll(metrics, panel("Table & Pickup Orders", content(filtersRow,
                new FlowPane(8, 8, apply, clear, refresh), status, paging, orders)));
    }

    private void applyFilters() {
        try {
            filters = new Filters(search.getText(), date(from), date(to), code(orderStatus), code(paymentStatus),
                    code(serviceType), code(paymentMethod), code(sort));
            cursors.clear();
            cursors.add(null);
            fetch(0);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            status.setText("Enter valid dates in order and keep search at 120 characters or fewer.");
        }
    }

    private void previous() {
        if (pageIndex > 0) {
            fetch(pageIndex - 1);
        }
    }

    private void next() {
        if (!nextCursor.isBlank()) {
            int target = pageIndex + 1;
            if (cursors.size() > target) {
                cursors.set(target, nextCursor);
            } else {
                cursors.add(nextCursor);
            }
            fetch(target);
        }
    }

    private void fetch(int target) {
        long request = ++revision;
        var selectedFilters = filters;
        String cursor = cursors.get(target);
        nextCursor = "";
        previous.setDisable(true);
        next.setDisable(true);
        pageLabel.setText("");
        orders.getItems().clear();
        renderMetrics(null);
        status.setText("Loading orders and payments…");
        orders.setPlaceholder(loading());
        AdminWorkspace.background(() -> api.orders(selectedFilters, cursor), (loaded, failure) -> {
            if (!current(request)) {
                return;
            }
            if (failure != null) {
                String message = explain(failure);
                status.setText(message);
                orders.setPlaceholder(label("No orders loaded.", "muted"));
                return;
            }
            if (!(loaded.get("items") instanceof com.google.gson.JsonArray)) {
                String message = "The server returned an incomplete orders response.";
                status.setText(message);
                orders.setPlaceholder(label(message, "muted"));
                return;
            }
            currency = text(loaded, "currency");
            pageIndex = target;
            nextCursor = text(loaded, "nextCursor");
            syncMethods(strings(loaded, "paymentMethods"));
            renderMetrics(object(loaded, "summary"));
            orders.getItems().setAll(rows(loaded, "items"));
            orders.setPlaceholder(label("No orders match these filters.", "muted"));
            status.setText(orders.getItems().isEmpty() ? "No matching orders." : "Connected to shared services.");
            pageLabel.setText("Page " + (pageIndex + 1));
            previous.setDisable(pageIndex == 0);
            next.setDisable(nextCursor.isBlank());
        });
    }

    private void openOrder(JsonObject selected) {
        UUID id;
        try {
            id = UUID.fromString(text(selected, "id"));
        } catch (IllegalArgumentException invalid) {
            status.setText("This order is missing a usable identifier.");
            return;
        }
        var back = button("Back to orders", () -> {
            revision++;
            screen.getChildren().setAll(list);
            scrollToTop.run();
        });
        var body = new VBox(12, loading());
        screen.getChildren().setAll(back, body);
        scrollToTop.run();
        long request = ++revision;
        AdminWorkspace.background(() -> api.order(id), (detail, failure) -> {
            if (!current(request)) {
                return;
            }
            if (failure != null) {
                body.getChildren().setAll(label(explain(failure), "feedback"),
                        button("Retry details", () -> openOrder(selected)));
            } else {
                body.getChildren().setAll(new OperationsDetails(detail, scrollToTop));
            }
        });
    }

    private boolean current(long request) {
        return request == revision && getScene() != null;
    }

    private String explain(Exception failure) {
        return switch (classify(failure)) {
            case SESSION -> {
                expired.run();
                yield "Your manager session ended.";
            }
            case UNAVAILABLE -> UNAVAILABLE_MESSAGE;
            case DISCONNECTED -> "Disconnected. Check the server connection and refresh.";
            case NOT_FOUND -> "This order is no longer available.";
            case FILTERS -> "The server rejected these filters. Check the values and refresh.";
            case ERROR -> "The server could not load this information. Try again.";
        };
    }

    private void renderMetrics(JsonObject summary) {
        metrics.getChildren().setAll(metric("Total orders", value(summary, "totalOrders")),
                metric("Active orders", value(summary, "activeOrders")),
                metric("Completed orders", value(summary, "completedOrders")),
                metric("Confirmed sales", amount(summary, "confirmedSalesMinor", currency)),
                metric("Outstanding balance", amount(summary, "outstandingMinor", currency)),
                metric("Pending / failed payments", paymentCounts(summary)));
    }

    private static String paymentCounts(JsonObject summary) {
        String pending = value(summary, "pendingPayments");
        String failed = value(summary, "failedPayments");
        return pending.equals(UNAVAILABLE) && failed.equals(UNAVAILABLE) ? UNAVAILABLE : pending + " / " + failed;
    }

    private void syncMethods(List<String> available) {
        var prior = paymentMethod.getValue();
        paymentMethod.getItems().setAll(new Choice("All methods", ""));
        for (String method : available.stream().filter(code -> code.matches("[A-Z0-9_]{1,32}")).distinct().toList()) {
            paymentMethod.getItems().add(new Choice(state(method), method));
        }
        if (prior != null && !prior.code().isEmpty() && !paymentMethod.getItems().contains(prior)) {
            paymentMethod.getItems().add(prior);
        }
        paymentMethod.getSelectionModel().select(Objects.requireNonNullElse(prior, paymentMethod.getItems().getFirst()));
    }

    private static Node metric(String title, String value) {
        var card = new VBox(5, label(title, "muted"), label(value, "catalog-emphasis"));
        card.setMinWidth(132);
        card.getStyleClass().add("catalog-stat");
        return card;
    }

    private static ProgressIndicator loading() {
        var indicator = new ProgressIndicator();
        indicator.setMaxSize(30, 30);
        indicator.setAccessibleText("Loading");
        return indicator;
    }

    private static DatePicker datePicker() {
        var picker = new DatePicker();
        picker.setPromptText("Any date");
        picker.setPrefWidth(134);
        return picker;
    }

    private static LocalDate date(DatePicker picker) {
        String entered = picker.getEditor().getText();
        return entered.isBlank() ? null : picker.getConverter().fromString(entered);
    }

    private static ComboBox<Choice> choices(String all, String... codes) {
        var box = new ComboBox<Choice>();
        box.getItems().add(new Choice(all, ""));
        for (String code : codes) {
            box.getItems().add(new Choice(state(code), code));
        }
        box.getSelectionModel().selectFirst();
        return box;
    }

    private static String code(ComboBox<Choice> box) {
        return box.getValue().code();
    }

    private record Choice(String title, String code) {
        @Override public String toString() { return title; }
    }
}
