package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.components.TicketCard;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import com.roseroots.kitchen.service.KitchenService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class OrderDetails extends VBox {
    private static final DateTimeFormatter SUBMITTED = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm:ss a xxx", Locale.ENGLISH);
    private final KitchenService service;
    private final Clock clock;
    private final Button backButton = new Button();
    private final VBox content = new VBox(9);
    private final Label elapsed = label("", "elapsed");
    private final FlowPane badges = new FlowPane(6, 4);
    private Ticket ticket;
    private long selectedTicketId;
    private boolean delayed;

    public OrderDetails(KitchenService service, Clock clock, Runnable back) {
        this.service = service;
        this.clock = clock;
        setSpacing(9);
        backButton.getStyleClass().addAll("filter", "back-to-orders");
        backButton.setOnAction(event -> back.run());
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(backButton, scroll);
    }

    public void open(long ticketId, String backDestination) {
        selectedTicketId = ticketId;
        backButton.setText("← " + backDestination);
        render(ticketId);
    }

    private void render(long ticketId) {
        ticket = service.activeTickets().stream().filter(order -> order.id() == ticketId).findFirst().orElse(null);
        content.getChildren().clear();
        if (ticket == null) {
            content.getChildren().add(label("This order is no longer active.", "empty"));
            return;
        }
        String type = ticket.type() == Type.DINE_IN ? "Dine-in" : "Takeaway · Pack for pickup";
        Label destination = label(ticket.destination() + "  ·  " + type, "detail-destination");
        HBox header = new HBox(10, label(ticket.reference(), "ticket-id"), destination);
        header.setAlignment(Pos.CENTER_LEFT);
        FlowPane progress = new FlowPane(7, 4);
        progress.getStyleClass().add("detail-progress");
        for (Status status : Status.LANES) {
            long count = ticket.items().stream().filter(item -> item.status() == status).count();
            Label stage = label(status.label + " · " + count, "detail-stage");
            stage.setAccessibleText(status.label + " items: " + count);
            if (status == ticket.status()) {
                stage.getStyleClass().add("detail-stage-current");
            }
            progress.getChildren().add(stage);
        }
        VBox overview = new VBox(7, header,
                label("Submitted " + SUBMITTED.format(ticket.orderedAt()), "detail-submitted"), badges, progress);
        overview.getStyleClass().add("ticket");
        updateBadges(OffsetDateTime.now(clock));
        tick();

        long ready = ticket.items().stream().filter(item -> item.status() == Status.READY).count();
        Label heading = label("Order items  ·  " + ready + " of " + ticket.items().size() + " ready", "detail-section");
        content.getChildren().add(overview);
        if (ticket.server() != null && !ticket.server().visitAllergy().isBlank()) {
            content.getChildren().add(label("Visit allergy: " + ticket.server().visitAllergy(), "allergen"));
        }
        content.getChildren().add(heading);
        for (var item : ticket.items()) {
            var row = TicketCard.itemRow(item, service.writesAvailable() ? selected -> {
                service.advanceItem(ticketId, selected.id(), selected.status());
                if (service.local()) {
                    render(ticketId);
                }
            } : null);
            row.getStyleClass().add("detail-item");
            content.getChildren().add(row);
        }
    }

    public void refresh() {
        render(selectedTicketId);
    }

    public void tick() {
        if (ticket != null) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            elapsed.setText("Elapsed " + TicketCard.elapsedText(ticket, now));
            if (delayed != ticket.delayedAt(now)) {
                updateBadges(now);
            }
        }
    }

    private void updateBadges(OffsetDateTime now) {
        delayed = ticket.delayedAt(now);
        badges.getChildren().setAll(label("Order: " + ticket.status().label, "normal"), elapsed);
        if (ticket.rush()) {
            badges.getChildren().add(label("RUSH", "rush"));
        }
        if (delayed) {
            badges.getChildren().add(label("DELAYED", "delayed"));
        }
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
