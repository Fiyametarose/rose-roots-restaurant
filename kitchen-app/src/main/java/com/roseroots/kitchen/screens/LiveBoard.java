package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.components.TicketCard;
import com.roseroots.kitchen.model.KitchenBoard;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.service.KitchenService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class LiveBoard extends VBox {
    private final KitchenService service;
    private final Clock clock;
    private final LongConsumer openDetails;
    private final Label active = label("0", "summary-number");
    private final EnumMap<Status, Label> summaryCounts = new EnumMap<>(Status.class);
    private final Label readyCaption = label("", "lane-caption");
    private final EnumMap<Status, VBox> lists = new EnumMap<>(Status.class);
    private final EnumMap<Status, Label> counts = new EnumMap<>(Status.class);
    private final List<TicketCard> cards = new ArrayList<>();
    private final Timeline timer;
    private Station station;

    public LiveBoard(KitchenService service, Clock clock, LongConsumer openDetails) {
        this.service = service;
        this.clock = clock;
        this.openDetails = openDetails;
        setSpacing(9);
        HBox lanes = new HBox(12);
        for (Status status : Status.LANES) {
            lanes.getChildren().add(lane(status));
        }
        ScrollPane laneScroller = new ScrollPane(lanes);
        laneScroller.getStyleClass().add("board-lanes");
        laneScroller.setFitToWidth(true);
        laneScroller.setFitToHeight(true);
        laneScroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(laneScroller, Priority.ALWAYS);
        getChildren().addAll(summary(), filters(), laneScroller);
        redraw();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private HBox summary() {
        HBox row = new HBox(8, label("Order Summary", "summary-title"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("summary");
        for (Status status : Status.LANES) {
            Label count = label("0", "summary-number");
            summaryCounts.put(status, count);
            row.getChildren().add(metric(status.label, count));
        }
        row.getChildren().add(metric("Total Active", active));
        return row;
    }

    private VBox metric(String name, Label count) {
        VBox tile = new VBox(0, count, label(name, "summary-caption"));
        tile.setAlignment(Pos.CENTER);
        tile.getStyleClass().add("summary-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tile, Priority.ALWAYS);
        return tile;
    }

    private FlowPane filters() {
        ToggleGroup group = new ToggleGroup();
        FlowPane row = new FlowPane(7, 5, label("Station", "filter-label"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefWrapLength(460);
        row.getStyleClass().add("filters");
        ToggleButton all = filter("All Orders", group, () -> select(null));
        row.getChildren().add(all);
        for (Station value : Station.values()) {
            ToggleButton stationFilter = filter(value.label, group, () -> select(value));
            if (!service.local()) {
                stationFilter.setDisable(true);
                stationFilter.setTooltip(new javafx.scene.control.Tooltip("Station assignments are unavailable from this API."));
            }
            row.getChildren().add(stationFilter);
        }
        all.setSelected(true);
        return row;
    }

    private ToggleButton filter(String text, ToggleGroup group, Runnable action) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("filter");
        button.setToggleGroup(group);
        button.setOnAction(event -> {
            Toggle selected = group.getSelectedToggle();
            if (selected == null) {
                button.setSelected(true);
            }
            action.run();
        });
        return button;
    }

    private void select(Station station) {
        this.station = station;
        redraw();
    }

    private VBox lane(Status status) {
        Label count = label("0", "lane-count");
        counts.put(status, count);
        String caption = switch (status) {
            case RECEIVED -> "Orders to start";
            case PREPARING -> "Currently being prepared";
            case READY -> "";
            default -> throw new IllegalArgumentException("Unsupported board lane");
        };
        VBox heading = new VBox(2, label(status.label, "lane-title"), status == Status.READY ? readyCaption : label(caption, "lane-caption"));
        HBox banner = new HBox(10, heading, spacer(), count);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().addAll("lane-heading", "lane-" + status.name().toLowerCase(Locale.ROOT));
        VBox list = new VBox(9);
        list.getStyleClass().add("ticket-list");
        lists.put(status, list);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox lane = new VBox(banner, scroll);
        lane.getStyleClass().add("lane");
        lane.setMinWidth(225);
        lane.setPrefWidth(330);
        HBox.setHgrow(lane, Priority.ALWAYS);
        return lane;
    }

    public void redraw() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Ticket> tickets = service.activeTickets();
        var columns = KitchenBoard.columns(tickets, station, now);
        KitchenBoard.summary(tickets).forEach((status, count) ->
                summaryCounts.get(status).setText(Long.toString(count)));
        active.setText(Integer.toString(tickets.size()));
        readyCaption.setText(station == null ? "All order items ready" : "Station items ready");
        cards.clear();
        for (Status status : Status.LANES) {
            var entries = columns.get(status);
            VBox list = lists.get(status);
            list.getChildren().clear();
            counts.get(status).setText(Integer.toString(entries.size()));
            if (entries.isEmpty()) {
                list.getChildren().add(label("No " + status.label.toLowerCase(Locale.ROOT) + " tickets", "empty"));
            }
            for (var entry : entries) {
                TicketCard card = new TicketCard(entry, now, service.local() ? expected -> {
                    service.advance(entry.ticket().id(), entry.station(), expected);
                    redraw();
                } : null, service.writesAvailable() ? item -> {
                    service.advanceItem(entry.ticket().id(), item.id(), item.status());
                    if (service.local()) {
                        redraw();
                    }
                } : null, () -> openDetails.accept(entry.ticket().id()));
                cards.add(card);
                list.getChildren().add(card);
            }
        }
    }

    private void tick() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (cards.stream().anyMatch(card -> card.delayChanged(now))) {
            redraw();
        } else {
            cards.forEach(card -> card.updateElapsed(now));
        }
    }

    public void stop() {
        timer.stop();
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
