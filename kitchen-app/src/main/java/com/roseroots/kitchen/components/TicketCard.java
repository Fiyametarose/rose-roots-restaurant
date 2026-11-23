package com.roseroots.kitchen.components;

import com.roseroots.kitchen.model.KitchenBoard.Card;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Item;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class TicketCard extends VBox {
    private static final DateTimeFormatter ORDERED = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH);
    private final Ticket ticket;
    private final boolean delayed;
    private final Label elapsed = label("", "elapsed");

    public TicketCard(Card entry, OffsetDateTime now, Consumer<Status> advance, Consumer<Item> advanceItem,
                      Runnable openDetails) {
        ticket = entry.ticket();
        delayed = ticket.delayedAt(now);
        setSpacing(6);
        getStyleClass().add("ticket");
        Label id = label(ticket.reference(), "ticket-id");
        if (ticket.server() != null) {
            id.setMaxWidth(190);
        }
        FlowPane heading = new FlowPane(6, 3, id, label(ticket.destination(), "destination"));
        Button details = new Button("Details");
        details.getStyleClass().addAll("item-action", "view-details");
        details.setAccessibleText("View details for " + ticket.reference());
        details.setOnAction(event -> openDetails.run());
        heading.getChildren().add(details);
        heading.setAlignment(Pos.CENTER_LEFT);
        String type = ticket.type() == Type.DINE_IN ? "Dine-in" : "Takeaway · Pack for pickup";
        FlowPane badges = new FlowPane(5, 3, priorityBadges(ticket, delayed), elapsed);
        getChildren().addAll(heading, badges,
                label(type + "  ·  Ordered " + ORDERED.format(ticket.orderedAt()), "metadata"));
        var activeItems = entry.items().stream().filter(item -> item.status() != Status.CANCELLED).toList();
        for (Item item : activeItems) {
            getChildren().add(itemRow(item, advance == null || ticket.items().size() > 1 ? advanceItem : null));
        }
        if (activeItems.stream().allMatch(item -> item.status() == Status.READY)) {
            String ready = ticket.status() != Status.READY ? "Station work ready"
                    : ticket.type() == Type.DINE_IN ? "Ready for table service" : "Ready for collection";
            getChildren().add(label(ready, "ready-note"));
        } else if (advance != null) {
            FlowPane actions = new FlowPane(6, 4);
            for (Status expected : new Status[]{Status.RECEIVED, Status.PREPARING}) {
                if (activeItems.stream().anyMatch(item -> item.status() == expected)) {
                    String scope = entry.station() == null ? "ticket" : "station";
                    String text = expected == Status.RECEIVED
                            ? "Start all new " + scope + " items" : "Mark preparing " + scope + " items ready";
                    Button action = new Button(text);
                    action.setWrapText(true);
                    action.getStyleClass().add(expected == Status.RECEIVED ? "start" : "mark-ready");
                    action.setTooltip(new Tooltip(
                            "Updates only eligible " + scope + " items for " + ticket.reference()
                                    + (entry.station() == null ? "" : " at " + entry.station().label)));
                    action.setOnAction(event -> advance.accept(expected));
                    actions.getChildren().add(action);
                }
            }
            getChildren().add(actions);
        }
        updateElapsed(now);
    }

    public static HBox priorityBadges(Ticket ticket, boolean delayed) {
        HBox badges = new HBox(5);
        if (ticket.rush()) {
            badges.getChildren().add(label("RUSH", "rush"));
        }
        if (delayed) {
            badges.getChildren().add(label("DELAYED", "delayed"));
        }
        if (badges.getChildren().isEmpty()) {
            badges.getChildren().add(label("Normal", "normal"));
        }
        return badges;
    }

    public static VBox itemRow(Item item, Consumer<Item> advance) {
        Label name = label(item.quantity() + " × " + item.name(), "item-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox row = new HBox(5, name);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox details = new VBox(2, row, label((item.station() == null ? "Station unavailable" : item.station().label) + "  ·  " + item.status().label, "metadata"));
        if (!item.options().isBlank()) {
            details.getChildren().add(label("Options: " + item.options(), "item-note"));
        }
        if (!item.instructions().isBlank()) {
            details.getChildren().add(label("Customer note: " + item.instructions(), "customer-note"));
        }
        if (!item.allergens().isBlank()) {
            details.getChildren().add(label("Allergen: " + item.allergens(), "allergen"));
        }
        if (!item.crossContact().isBlank()) {
            details.getChildren().add(label("Cross-contact: " + item.crossContact(), "allergen"));
        }
        if (item.actionable() && advance != null) {
            String action = item.status() == Status.RECEIVED ? "Start item" : "Mark item ready";
            Button button = new Button(action);
            button.setAccessibleText(action + ": " + item.name());
            button.getStyleClass().addAll("item-action", item.status() == Status.RECEIVED ? "item-start" : "item-ready");
            button.setOnAction(event -> advance.accept(item));
            row.getChildren().add(button);
        }
        if (item.server() != null && !item.server().cleared()) {
            details.getChildren().add(label("Allergy review: " + item.server().allergyReviewStatus()
                    + (item.server().unresolvedRisk() ? " · Unresolved risk" : ""), "allergen"));
        }
        details.getStyleClass().add("item-row");
        return details;
    }

    public boolean delayChanged(OffsetDateTime now) {
        return delayed != ticket.delayedAt(now);
    }

    public void updateElapsed(OffsetDateTime now) {
        long seconds = Math.max(0, Duration.between(ticket.orderedAt(), now).toSeconds());
        elapsed.setText("Elapsed " + elapsedText(ticket, now));
        elapsed.setAccessibleText("Elapsed " + seconds / 60 + " minutes and " + seconds % 60 + " seconds");
    }

    public static String elapsedText(Ticket ticket, OffsetDateTime now) {
        long seconds = Math.max(0, Duration.between(ticket.orderedAt(), now).toSeconds());
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
