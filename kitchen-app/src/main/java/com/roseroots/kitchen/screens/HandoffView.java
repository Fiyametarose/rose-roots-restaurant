package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.model.KitchenBoard;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Type;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.service.KitchenService;
import java.util.List;
import java.util.stream.Collectors;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class HandoffView extends VBox {
    private final KitchenService service;
    private final Label total = label("", "lane-count");
    private final VBox dineIn = new VBox(8);
    private final VBox takeaway = new VBox(8);

    public HandoffView(KitchenService service) {
        this.service = service;
        setSpacing(10);
        Label scope = label("All items prepared", "handoff-banner-text");
        HBox.setHgrow(scope, Priority.ALWAYS);
        scope.setMaxWidth(Double.MAX_VALUE);
        HBox banner = new HBox(8, scope, total);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("handoff-banner");
        HBox sections = new HBox(10,
                section("Dine-in", "Ready for table service", dineIn),
                section("Takeaway", "Ready for collection", takeaway));
        VBox.setVgrow(sections, Priority.ALWAYS);
        getChildren().addAll(banner, sections);
    }

    public void refresh() {
        var groups = KitchenBoard.handoff(service.activeTickets());
        List<Ticket> tables = groups.get(Type.DINE_IN);
        List<Ticket> pickups = groups.get(Type.TAKEAWAY);
        total.setText((tables.size() + pickups.size()) + " ready");
        populate(dineIn, tables, "No table orders ready");
        populate(takeaway, pickups, "No takeaway orders ready");
    }

    private static VBox section(String title, String subtitle, VBox cards) {
        VBox heading = new VBox(2, label(title, "lane-title"), label(subtitle, "lane-caption"));
        heading.getStyleClass().add("lane-heading");
        cards.getStyleClass().add("ticket-list");
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox section = new VBox(heading, scroll);
        section.getStyleClass().add("lane");
        section.setMinWidth(0);
        section.setPrefWidth(240);
        HBox.setHgrow(section, Priority.ALWAYS);
        return section;
    }

    private void populate(VBox list, List<Ticket> tickets, String empty) {
        list.getChildren().clear();
        if (tickets.isEmpty()) {
            list.getChildren().add(label(empty, "empty"));
            return;
        }
        for (Ticket ticket : tickets) {
            var readyItems = ticket.items().stream().filter(item -> item.status() == Status.READY).toList();
            String summary = readyItems.stream().map(item -> item.quantity() + " × " + item.name())
                    .collect(Collectors.joining("  ·  "));
            String instruction = ticket.type() == Type.DINE_IN
                    ? "Ready for table service" : "Ready for collection · Verify pickup reference and packing";
            VBox card = new VBox(5, label(ticket.destination() + "  ·  " + ticket.reference(), "destination"),
                    label(summary, "item-name"), label(instruction, "ready-note"));
            card.getStyleClass().add("ticket");
            for (Ticket.Item item : readyItems) {
                if (!item.allergens().isBlank()) {
                    card.getChildren().add(label(item.name() + " · Allergen: " + item.allergens(), "allergen"));
                }
            }
            if (!service.local() && ticket.server() != null) {
                String action = ticket.type() == Type.DINE_IN ? "Confirm served" : "Confirm collected";
                var confirm = new Button(action);
                confirm.setDisable(!service.writesAvailable());
                confirm.setOnAction(event -> {
                    var dialog = new Alert(Alert.AlertType.CONFIRMATION, action + " for " + ticket.destination() + "?",
                            ButtonType.OK, ButtonType.CANCEL);
                    dialog.initOwner(getScene().getWindow());
                    dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(accepted -> service.handoff(ticket));
                });
                card.getChildren().add(confirm);
            }
            list.getChildren().add(card);
        }
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
