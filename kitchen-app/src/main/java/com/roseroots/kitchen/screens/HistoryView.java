package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.model.HistoryQuery;
import com.roseroots.kitchen.model.OrderCompletion.Status;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Type;
import com.roseroots.kitchen.service.KitchenService;
import com.roseroots.kitchen.service.KitchenSync;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class HistoryView extends VBox {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d · h:mm a", Locale.ENGLISH);
    private final KitchenService service;
    private final KitchenSync sync;
    private final TextField search = new TextField();
    private final ComboBox<String> status = new ComboBox<>();
    private final ComboBox<String> type = new ComboBox<>();
    private final Label connection = label("", "connection-state");
    private final Label connectionDetails = label("", "item-note");
    private final Label count = label("", "lane-caption");
    private final VBox cards = new VBox(7);

    public HistoryView(KitchenService service, KitchenSync sync) {
        this.service = service;
        this.sync = sync;
        setSpacing(10);
        search.setId("history-search");
        search.setPromptText("Order ID, table, pickup or item");
        search.setPrefColumnCount(22);
        search.setAccessibleText("Search order history");
        search.textProperty().addListener((obs, before, after) -> refresh());
        status.setId("history-status");
        status.setAccessibleText("Order status");
        status.getItems().add("All statuses");
        for (Status value : Status.values()) {
            status.getItems().add(text(value));
        }
        status.getSelectionModel().selectFirst();
        status.setOnAction(event -> refresh());
        type.setId("history-type");
        type.setAccessibleText("Order type");
        type.getItems().addAll("All order types", "Dine-in", "Takeaway");
        type.getSelectionModel().selectFirst();
        type.setOnAction(event -> refresh());
        FlowPane filters = new FlowPane(6, 5, search, status, type);
        filters.getStyleClass().add("ticket-list");
        cards.getStyleClass().add("ticket-list");
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox history = new VBox(heading("Order history", count), filters, scroll);
        history.getStyleClass().add("lane");
        history.setMinWidth(0);
        history.setPrefWidth(680);
        HBox.setHgrow(history, Priority.ALWAYS);
        connection.getStyleClass().add("normal");
        VBox details = new VBox(8, connection, connectionDetails);
        details.getStyleClass().add("ticket-list");
        VBox syncPanel = new VBox(heading("Connection status", null), details);
        syncPanel.getStyleClass().add("lane");
        syncPanel.setPrefWidth(300);
        syncPanel.setMinWidth(180);
        syncPanel.setMaxWidth(300);
        syncPanel.setMaxHeight(Region.USE_PREF_SIZE);
        HBox columns = new HBox(10, history, syncPanel);
        VBox.setVgrow(columns, Priority.ALWAYS);
        getChildren().add(columns);
    }

    public void refresh() {
        refreshConnection();
        int statusIndex = status.getSelectionModel().getSelectedIndex();
        int typeIndex = type.getSelectionModel().getSelectedIndex();
        var result = HistoryQuery.filter(service.historyTickets(), search.getText(),
                statusIndex > 0 ? Status.values()[statusIndex - 1] : null,
                typeIndex > 0 ? Type.values()[typeIndex - 1] : null);
        count.setText(result.size() + (result.size() == 1 ? " matching order" : " matching orders"));
        cards.getChildren().clear();
        result.forEach(ticket -> cards.getChildren().add(card(ticket)));
        if (result.isEmpty()) {
            cards.getChildren().add(label("No orders match these filters", "empty"));
        }
    }

    public void refreshConnection() {
        KitchenSync.Status current = sync.status();
        connection.setText(current.label);
        connectionDetails.setText(switch (current) {
            case LOCAL -> "Orders and changes are held in memory for this session. The shared backend is unavailable and server synchronization is inactive.";
            case OFFLINE -> "The shared server is unavailable. Order updates require a connection.";
            case CONNECTED -> "Connected to the shared server.";
        });
    }

    private static VBox card(Ticket ticket) {
        var completion = ticket.completion();
        String orderType = ticket.type() == Type.DINE_IN ? "Dine-in" : "Takeaway";
        String items = ticket.items().stream().map(item -> item.quantity() + " × " + item.name())
                .collect(Collectors.joining(" · "));
        String reached = completion.at() == null ? "Ready time unavailable"
                : text(completion.status()) + " " + TIME.format(completion.at());
        String badge = switch (completion.status()) {
            case READY -> "delayed";
            case SERVED, COLLECTED -> "normal";
            case CANCELLED -> "rush";
        };
        FlowPane headline = new FlowPane(6, 4, label(ticket.reference(), "ticket-id"),
                label(ticket.destination(), "destination"), label(orderType, "metadata"),
                label(text(completion.status()), badge));
        VBox card = new VBox(4, headline, label(items, "item-name"),
                label("Ordered " + TIME.format(ticket.orderedAt()) + " · " + reached, "metadata"));
        card.getStyleClass().addAll("ticket", "history-card");
        return card;
    }

    private static VBox heading(String title, Label subtitle) {
        VBox heading = new VBox(3, label(title, "lane-title"));
        heading.getStyleClass().add("lane-heading");
        if (subtitle != null) {
            heading.getChildren().add(subtitle);
        }
        return heading;
    }

    private static String text(Status value) {
        return value.name().charAt(0) + value.name().substring(1).toLowerCase(Locale.ROOT);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
