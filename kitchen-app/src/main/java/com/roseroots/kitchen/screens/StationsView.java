package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.components.TicketCard;
import com.roseroots.kitchen.model.KitchenBoard;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.service.KitchenService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class StationsView extends VBox {
    private final KitchenService service;
    private final Clock clock;
    private final LongConsumer openDetails;
    private final Label total = label("", "lane-count");
    private final Map<Station, VBox> lists = new EnumMap<>(Station.class);
    private final Map<Station, Map<Status, Label>> counters = new EnumMap<>(Station.class);
    private final Map<Ticket, List<Label>> elapsedLabels = new LinkedHashMap<>();
    private List<Ticket> snapshot;
    private List<Long> delayedIds;

    public StationsView(KitchenService service, Clock clock, LongConsumer openDetails) {
        this.service = service;
        this.clock = clock;
        this.openDetails = openDetails;
        setSpacing(10);
        Label heading = label("Preparation stations", "handoff-banner-text");
        heading.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(heading, Priority.ALWAYS);
        HBox banner = new HBox(10, heading, total);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("handoff-banner");
        HBox lanes = new HBox(10);
        for (Station station : Station.values()) {
            lanes.getChildren().add(lane(station));
        }
        ScrollPane scroller = new ScrollPane(lanes);
        scroller.setFitToHeight(true);
        scroller.setFitToWidth(true);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        getChildren().addAll(banner, scroller);
    }

    public void refresh() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Ticket> tickets = service.activeTickets();
        List<Long> overdue = tickets.stream().filter(ticket -> ticket.delayedAt(now)).map(Ticket::id).toList();
        if (!tickets.equals(snapshot) || !overdue.equals(delayedIds)) {
            snapshot = tickets;
            delayedIds = overdue;
            render(tickets, now);
        }
        elapsedLabels.forEach((ticket, labels) -> labels.forEach(label ->
                label.setText("Elapsed " + TicketCard.elapsedText(ticket, now))));
    }

    private VBox lane(Station station) {
        FlowPane counts = new FlowPane(6, 4);
        Map<Status, Label> stationCounts = new EnumMap<>(Status.class);
        counters.put(station, stationCounts);
        for (Status status : Status.values()) {
            Label metric = label("", "station-count");
            stationCounts.put(status, metric);
            counts.getChildren().add(metric);
        }
        VBox heading = new VBox(5, label(station.label, "lane-title"), counts);
        heading.getStyleClass().add("lane-heading");
        VBox list = new VBox(8);
        list.getStyleClass().add("ticket-list");
        lists.put(station, list);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox lane = new VBox(heading, scroll);
        lane.getStyleClass().add("lane");
        lane.setMinWidth(230);
        lane.setPrefWidth(330);
        HBox.setHgrow(lane, Priority.ALWAYS);
        return lane;
    }

    private void render(List<Ticket> tickets, OffsetDateTime now) {
        elapsedLabels.clear();
        long itemCount = 0;
        for (Station station : Station.values()) {
            var queue = KitchenBoard.stationQueue(tickets, station, now);
            var list = lists.get(station);
            list.getChildren().clear();
            for (Status status : Status.values()) {
                long count = queue.counts().get(status);
                String name = status == Status.RECEIVED ? "Pending" : status.label;
                counters.get(station).get(status).setText(name + " " + count);
                itemCount += count;
            }
            if (queue.cards().isEmpty()) {
                list.getChildren().add(label("No items assigned", "empty"));
            }
            for (var entry : queue.cards()) {
                list.getChildren().add(card(entry, now));
            }
        }
        total.setText(itemCount + " items across all stations");
    }

    private VBox card(KitchenBoard.Card entry, OffsetDateTime now) {
        Ticket ticket = entry.ticket();
        Button details = new Button("Details");
        details.getStyleClass().addAll("item-action", "view-details");
        details.setAccessibleText("View details for " + ticket.reference() + " at " + entry.station().label);
        details.setOnAction(event -> openDetails.accept(ticket.id()));
        FlowPane heading = new FlowPane(6, 4, label(ticket.reference(), "ticket-id"),
                label(ticket.destination(), "destination"), details);
        heading.setAlignment(Pos.CENTER_LEFT);
        Label elapsed = label("", "elapsed");
        elapsedLabels.computeIfAbsent(ticket, key -> new java.util.ArrayList<>()).add(elapsed);
        FlowPane badges = new FlowPane(5, 4, TicketCard.priorityBadges(ticket, ticket.delayedAt(now)), elapsed);
        VBox card = new VBox(5, heading, badges);
        card.getStyleClass().addAll("ticket", "station-card");
        for (var item : entry.items()) {
            card.getChildren().add(TicketCard.itemRow(item, selected -> {
                service.advanceItem(ticket.id(), selected.id(), selected.status());
                refresh();
            }));
        }
        return card;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
