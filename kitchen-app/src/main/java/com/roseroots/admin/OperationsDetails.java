package com.roseroots.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import static com.roseroots.admin.CatalogControls.*;
import static com.roseroots.admin.OperationsPresentation.*;

final class OperationsDetails extends VBox {
    private final JsonObject detail;
    private final JsonObject order;
    private final String currency;

    OperationsDetails(JsonObject detail, Runnable scrollToTop) {
        super(14);
        this.detail = detail;
        order = object(detail, "order");
        currency = text(detail, "currency");
        var area = new VBox(orderDetails());
        ToggleGroup tabs = new ToggleGroup();
        ToggleButton summary = tab("Order details", tabs, () -> {
            area.getChildren().setAll(orderDetails());
            scrollToTop.run();
        });
        ToggleButton payments = tab("Payment details", tabs, () -> {
            area.getChildren().setAll(paymentDetails());
            scrollToTop.run();
        });
        summary.setSelected(true);
        getChildren().setAll(label("Order " + value(order, "orderNumber"), "catalog-emphasis"),
                new FlowPane(8, 8, summary, payments), area);
    }

    private Node orderDetails() {
        var header = panel("Order information", content(fields(
                entry("Order identifier", value(order, "id")),
                entry("Dining session", value(detail, "sessionId")),
                entry("Table / pickup", value(order, "serviceReference")),
                entry("Service type", state(text(order, "serviceType"))),
                entry("Submitted", timestamp(order, "submittedAt")),
                entry("Order status", state(text(order, "orderStatus"))),
                entry("Payment status", state(text(order, "paymentStatus"))))));
        var totals = panel("Accepted order totals", totals(object(detail, "totals")));
        return new VBox(14, paired(header, totals),
                panel("Submitted items", content(list(detail, "items", "No submitted items recorded.", this::line))),
                panel("Order status history", content(list(detail, "history", "No status changes recorded.",
                        row -> entry(timestamp(row, "timestamp"), state(text(row, "status")))))));
    }

    private Node line(JsonObject item) {
        var elements = new ArrayList<Node>();
        elements.add(label(value(item, "quantity") + " × " + value(item, "name"), "catalog-emphasis"));
        elements.add(fields(entry("Confirmed unit price", amount(item, "baseUnitMinor", currency)),
                entry("Confirmed line total", amount(item, "extendedMinor", currency)),
                entry("Preparation", state(text(item, "preparationStatus")))));
        if (!text(item, "instructions").isBlank()) {
            elements.add(entry("Customer instructions", text(item, "instructions")));
        }
        var options = rows(item, "selections");
        if (!options.isEmpty()) {
            var group = new VBox(5, label("Selected options and extras", "field-label"));
            options.forEach(option -> group.getChildren().add(entry(value(option, "name"),
                    amount(option, "adjustmentMinor", currency) + " per item")));
            elements.add(group);
        }
        var section = new VBox(6);
        section.getStyleClass().add("operations-record");
        section.getChildren().addAll(elements);
        return section;
    }

    private Node paymentDetails() {
        var paymentSummary = panel("Payment oversight", content(
                entry("Order payment status", state(text(order, "paymentStatus"))),
                label("Financial actions are unavailable. This view is read-only.", "muted")));
        var linkedBill = object(detail, "sessionBill");
        var billContent = content(label("Combined session bill unavailable.", "muted"));
        if (linkedBill != null) {
            billContent.getChildren().setAll(entry("Dining session", value(linkedBill, "sessionId")),
                    totals(object(linkedBill, "totals")),
                    entry("Outstanding balance", amount(linkedBill, "outstandingMinor", currency)));
        }
        return new VBox(14, paired(paymentSummary, panel("Combined session bill", billContent)),
                panel("Payments & receipts", content(list(detail, "payments", "No payment transactions recorded.",
                        this::payment, new FlowPane(12, 12)))));
    }

    private Node payment(JsonObject transaction) {
        var facts = new VBox(8, label(state(text(transaction, "method")), "catalog-emphasis"),
                fields(entry("State", state(text(transaction, "status"))),
                entry("Transaction type", state(text(transaction, "kind"))),
                entry("Amount", amount(transaction, "amountMinor", currency)),
                entry("Transaction reference", value(transaction, "reference")),
                entry("Timestamp", timestamp(transaction, "timestamp")),
                entry("Associated order", value(transaction, "orderId")),
                entry("Associated session", value(transaction, "sessionId"))));
        var receipt = object(transaction, "receipt");
        if (receipt == null) {
            facts.getChildren().add(label("No issued receipt supplied.", "muted"));
        } else {
            facts.getChildren().add(fields(entry("Receipt number", value(receipt, "number")),
                    entry("Receipt issued", timestamp(receipt, "issuedAt")),
                    entry("Receipt amount", amount(receipt, "amountMinor", currency))));
        }
        facts.getStyleClass().add("operations-record");
        facts.setPrefWidth(315);
        return facts;
    }

    private VBox totals(JsonObject amounts) {
        return content(fields(entry("Subtotal", amount(amounts, "subtotalMinor", currency)),
                entry("Service charges", amount(amounts, "serviceMinor", currency)),
                entry("Taxes", amount(amounts, "taxMinor", currency)),
                entry("Total", amount(amounts, "totalMinor", currency))));
    }

    private static Node list(JsonObject value, String field, String empty,
                             java.util.function.Function<JsonObject, Node> render) {
        return list(value, field, empty, render, new VBox(8));
    }

    private static Node list(JsonObject value, String field, String empty,
                             java.util.function.Function<JsonObject, Node> render, Pane result) {
        if (!(value.get(field) instanceof JsonArray data)) {
            return label(UNAVAILABLE, "muted");
        }
        if (data.isEmpty()) {
            return label(empty, "muted");
        }
        rows(value, field).forEach(record -> result.getChildren().add(render.apply(record)));
        return result;
    }

    private static VBox entry(String title, String value) {
        Label key = label(title, "field-label");
        Label display = label(value, "muted");
        return new VBox(2, key, display);
    }

    private static FlowPane fields(Node... fields) {
        return new FlowPane(18, 10, fields);
    }

    private static ToggleButton tab(String title, ToggleGroup group, Runnable onSelect) {
        var button = new ToggleButton(title);
        button.setToggleGroup(group);
        button.getStyleClass().add("operations-tab");
        button.setOnAction(event -> {
            button.setSelected(true);
            onSelect.run();
        });
        return button;
    }
}
