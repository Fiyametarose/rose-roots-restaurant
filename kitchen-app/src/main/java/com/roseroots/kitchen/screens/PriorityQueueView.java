package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.components.TicketCard;
import com.roseroots.kitchen.model.KitchenBoard;
import com.roseroots.kitchen.model.StaffMember;
import com.roseroots.kitchen.model.RushChange.Action;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.service.KitchenService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

public final class PriorityQueueView extends ScrollPane {
    private static final DateTimeFormatter CHANGED = DateTimeFormatter.ofPattern("MMM d · h:mm a xxx", Locale.ENGLISH);
    private final KitchenService service;
    private final Clock clock;
    private final VBox pending = new VBox(5);
    private final VBox preparing = new VBox(5);
    private final Label pendingCount = label("", "priority-stat-count");
    private final Label rushCount = label("", "priority-stat-count");
    private final Label delayedCount = label("", "priority-stat-count");
    private final Label target = label("", "priority-stat-count");
    private final Label selection = label("", "detail-section");
    private final Label changeInfo = label("", "item-note");
    private final Label feedback = label("", "customer-note");
    private final Label permission = label("", "item-note");
    private final ComboBox<StaffMember> staff = new ComboBox<>();
    private final TextArea reason = new TextArea();
    private final Button apply = new Button();
    private final ToggleGroup rows = new ToggleGroup();
    private final Map<Ticket, Label> timers = new LinkedHashMap<>();
    private List<QueueEntry> waitingEntries;
    private List<QueueEntry> activeEntries;
    private Ticket selected;

    public PriorityQueueView(KitchenService service, List<StaffMember> staffMembers,
                             Consumer<String> selectProfile, Clock clock) {
        this.service = service;
        this.clock = clock;
        pending.getStyleClass().add("ticket-list");
        preparing.getStyleClass().add("ticket-list");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        HBox metrics = new HBox(8, metric("Waiting to start", pendingCount),
                metric("Rush orders", rushCount), metric("Delayed orders", delayedCount), metric("Delay target", target));
        VBox queue = new VBox(9,
                section("Waiting to start", "Rush → Delayed → Normal. Oldest first within each group.", pending),
                section("In progress", "Shown oldest first. Priority changes leave preparation in progress.", preparing));
        VBox approval = approval(staffMembers, selectProfile);
        BorderPane panels = new BorderPane(queue);
        panels.setBottom(approval);
        viewportBoundsProperty().addListener((observable, previous, viewport) -> {
            boolean sideBySide = viewport.getWidth() >= 780;
            if (sideBySide && panels.getRight() != approval) {
                panels.setBottom(null);
                panels.setRight(approval);
                BorderPane.setMargin(approval, new javafx.geometry.Insets(0, 0, 0, 9));
            } else if (!sideBySide && panels.getBottom() != approval) {
                panels.setRight(null);
                panels.setBottom(approval);
                BorderPane.setMargin(approval, new javafx.geometry.Insets(9, 0, 0, 0));
            }
        });
        BorderPane.setMargin(approval, new javafx.geometry.Insets(0, 0, 0, 9));
        VBox content = new VBox(10, metrics, panels);
        content.getStyleClass().add("priority-content");
        setContent(content);
    }

    public void tick() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Ticket> tickets = service.activeTickets();
        var waiting = entries(tickets, Status.RECEIVED, now);
        var active = entries(tickets, Status.PREPARING, now);
        if (!waiting.equals(waitingEntries) || !active.equals(active)) {
            waitingEntries = waiting;
            activeEntries = active;
            long selectedId = selected == null ? -1 : selected.id();
            timers.clear();
            populate(pending, waiting, selectedId, "No orders waiting");
            populate(preparing, active, selectedId, "No orders in progress");
            if (rows.getSelectedToggle() == null) {
                rows.getToggles().stream().findFirst().ifPresent(toggle -> toggle.setSelected(true));
            }
            var toggle = rows.getSelectedToggle();
            select(toggle == null ? null : (Ticket) toggle.getUserData());
        }
        timers.forEach((ticket, label) -> label.setText("Elapsed " + TicketCard.elapsedText(ticket, now)));
        pendingCount.setText(Integer.toString(waiting.size()));
        rushCount.setText(Long.toString(tickets.stream().filter(t -> t.status() != Status.READY && t.rush()).count()));
        delayedCount.setText(Long.toString(tickets.stream().filter(t -> t.delayedAt(now)).count()));
        List<java.time.Duration> targets = tickets.stream().map(Ticket::delayTarget).distinct().toList();
        target.setText(targets.isEmpty() ? "—" : targets.size() == 1 ? targets.getFirst().toMinutes() + " min" : "Per order");
    }

    private record QueueEntry(Ticket ticket, boolean delayed) {}

    private static List<QueueEntry> entries(List<Ticket> tickets, Status status, OffsetDateTime now) {
        return KitchenBoard.priorityQueue(tickets, status, now).stream()
                .map(ticket -> new QueueEntry(ticket, ticket.delayedAt(now))).toList();
    }

    private void populate(VBox list, List<QueueEntry> entries, long selectedId, String empty) {
        for (var child : list.getChildren()) {
            if (child instanceof ToggleButton row) {
                row.setToggleGroup(null);
            }
        }
        list.getChildren().clear();
        if (entries.isEmpty()) {
            list.getChildren().add(label(empty, "empty"));
            return;
        }
        for (QueueEntry entry : entries) {
            Ticket ticket = entry.ticket();
            Label elapsed = label("", "elapsed");
            timers.put(ticket, elapsed);
            Label id = label(ticket.reference() + "  ·  " + ticket.destination(), "destination");
            HBox badges = new HBox(5, label(ticket.status().label, "normal"));
            if (ticket.rush()) {
                badges.getChildren().add(label("RUSH", "rush"));
            }
            if (entry.delayed()) {
                badges.getChildren().add(label("DELAYED", "delayed"));
            }
            if (!ticket.rush() && !entry.delayed()) {
                badges.getChildren().add(label("Normal priority", "normal"));
            }
            badges.getChildren().add(elapsed);
            ToggleButton row = new ToggleButton();
            row.setGraphic(new VBox(4, id, badges));
            row.setAccessibleText(ticket.reference() + ", " + ticket.destination() + ", " + ticket.status().label
                    + (ticket.rush() ? ", Rush" : "") + (entry.delayed() ? ", Delayed" : ""));
            row.getStyleClass().add("priority-row");
            row.setMaxWidth(Double.MAX_VALUE);
            row.setMinHeight(Region.USE_PREF_SIZE);
            row.setPrefHeight(60);
            row.setMaxHeight(Region.USE_PREF_SIZE);
            row.setToggleGroup(rows);
            row.setUserData(ticket);
            row.setSelected(ticket.id() == selectedId);
            row.setOnAction(event -> {
                row.setSelected(true);
                select(ticket);
            });
            list.getChildren().add(row);
        }
    }

    private void select(Ticket ticket) {
        if (selected == null || ticket == null || selected.id() != ticket.id()) {
            reason.clear();
            feedback.setText("");
        }
        selected = ticket;
        selection.setText(ticket == null ? "Select an unfinished order" : ticket.reference() + "  ·  " + ticket.destination());
        if (ticket == null || ticket.rushHistory().isEmpty()) {
            changeInfo.setText("");
        } else {
            var change = ticket.rushHistory().getLast();
            changeInfo.setText((change.action() == Action.APPLY_RUSH ? "Rush applied" : "Rush removed") + " by "
                    + change.actor().name() + " · " + CHANGED.format(change.changedAt()) + "\n" + change.reason());
        }
        updateControls();
    }

    private VBox approval(List<StaffMember> staffMembers, Consumer<String> selectProfile) {
        if (staffMembers.isEmpty()) {
            VBox unavailable = new VBox(label("Rush changes are unavailable in connected mode for this phase.", "item-note"));
            unavailable.getStyleClass().add("ticket-list");
            return section("Rush authorization", "Server authorization required", unavailable);
        }
        staff.getItems().setAll(staffMembers);
        staff.setConverter(new StringConverter<>() {
            @Override
            public String toString(StaffMember member) {
                return member == null ? "" : member.name() + " · " + member.role().label;
            }

            @Override
            public StaffMember fromString(String ignored) {
                throw new UnsupportedOperationException("Select an existing staff profile");
            }
        });
        staff.setMaxWidth(Double.MAX_VALUE);
        staff.valueProperty().addListener((observable, previous, current) -> {
            selectProfile.accept(current == null ? null : current.id());
            reason.clear();
            feedback.setText("");
            updateControls();
        });
        staff.getSelectionModel().selectFirst();
        reason.setPromptText("Required for every Rush change");
        reason.setWrapText(true);
        reason.setPrefRowCount(3);
        apply.getStyleClass().add("rush-action");
        apply.setOnAction(event -> applyChange());
        Label staffLabel = label("Local staff profile", "item-name");
        staffLabel.setLabelFor(staff);
        Label reasonLabel = label("Reason", "item-name");
        reasonLabel.setLabelFor(reason);
        VBox fields = new VBox(7, selection, changeInfo, staffLabel, staff, permission,
                reasonLabel, reason, apply, feedback);
        fields.getStyleClass().add("ticket-list");
        VBox section = section("Rush authorization", "Supervisor or manager approval required", fields);
        section.setPrefWidth(325);
        updateControls();
        return section;
    }

    private void updateControls() {
        StaffMember actor = staff.getValue();
        boolean allowed = actor != null && actor.role().canAuthorizeRush();
        permission.setText(allowed ? "This local profile can authorize Rush." : "This profile cannot authorize Rush. Supervisor or manager required.");
        apply.setText(selected != null && selected.rush() ? "Remove Rush" : "Apply Rush");
        reason.setDisable(selected == null || !allowed);
        apply.setDisable(selected == null || !allowed);
    }

    private void applyChange() {
        if (selected == null || staff.getValue() == null) {
            return;
        }
        boolean enable = !selected.rush();
        try {
            service.changeRush(selected.id(), enable, reason.getText());
            reason.clear();
            tick();
            feedback.setText(enable ? "Rush applied." : "Rush removed.");
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            feedback.setText(ex.getMessage());
        }
    }

    private static VBox section(String title, String description, VBox body) {
        VBox heading = new VBox(2, label(title, "lane-title"), label(description, "lane-caption"));
        heading.getStyleClass().add("lane-heading");
        VBox panel = new VBox(heading, body);
        panel.getStyleClass().add("lane");
        return panel;
    }

    private static VBox metric(String name, Label value) {
        VBox metric = new VBox(2, value, label(name, "metadata"));
        metric.setAlignment(Pos.CENTER_LEFT);
        metric.getStyleClass().add("ticket");
        metric.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(metric, Priority.ALWAYS);
        return metric;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
