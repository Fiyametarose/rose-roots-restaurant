package com.roseroots.kitchen.screens;

import com.roseroots.kitchen.components.TicketCard;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import com.roseroots.kitchen.service.KitchenService;
import com.roseroots.kitchen.service.http.SpringBootKitchenService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
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
    private Ticket historical;
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
        historical = null;
        selectedTicketId = ticketId;
        backButton.setText("← " + backDestination);
        render(ticketId);
    }

    public void openHistorical(Ticket archived) {
        historical = archived;
        backButton.setText("← History & Sync");
        render(archived.id());
    }

    private void render(long ticketId) {
        ticket = historical == null
                ? service.activeTickets().stream().filter(order -> order.id() == ticketId).findFirst().orElse(null)
                : historical;
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
        long active = ticket.items().stream().filter(item -> item.status() != Status.CANCELLED).count();
        Label heading = label("Order items  ·  " + ready + " of " + active + " ready", "detail-section");
        content.getChildren().add(overview);
        if (ticket.server() != null && ticket.server().handoff() != null) {
            var handoff = ticket.server().handoff();
            content.getChildren().add(label(handoff.summary() + " · " + SUBMITTED.format(handoff.completedAt()), "metadata"));
        }
        if (ticket.server() != null && !ticket.server().visitAllergy().isBlank()) {
            content.getChildren().add(label("Visit allergy: " + ticket.server().visitAllergy(), "allergen"));
        }
        content.getChildren().add(heading);
        for (var item : ticket.items()) {
            var remote = service instanceof SpringBootKitchenService rest ? rest : null;
            var staff = remote == null ? null : remote.signedInStaff();
            boolean owner = staff != null && staff.id().equals(item.server().chefId());
            boolean offDuty = item.server() != null && Boolean.FALSE.equals(item.server().chefOnDuty());
            var row = TicketCard.itemRow(item, historical == null && service.writesAvailable()
                    && (service.local() || owner && !offDuty) ? selected -> {
                service.advanceItem(ticketId, selected);
                if (service.local()) {
                    render(ticketId);
                }
            } : null);
            if (remote != null && item.server() != null) {
                String chef = item.server().chefName();
                row.getChildren().add(label("Responsible Chef: " + (chef == null ? "Unassigned" : chef), "metadata"));
                if (historical == null && offDuty && (item.status() == Status.RECEIVED || item.status() == Status.PREPARING)) {
                    row.getChildren().add(label("Responsible Chef unavailable. Supervisor reassignment is needed.", "item-note"));
                }
                if (staff != null && !staff.role().equals("WAITER")) {
                    var actions = new FlowPane(6, 5);
                    var history = new Button("Preparation history");
                    history.setOnAction(event -> showHistory(remote, item, this));
                    actions.getChildren().add(history);
                    if (historical == null && item.status() == Status.RECEIVED && staff.role().equals("CHEF") && (chef == null || owner)) {
                        var claim = new Button(owner ? "Release item" : "Claim Item");
                        claim.setDisable(!remote.writesAvailable() || !owner && item.station() == null);
                        claim.setOnAction(event -> remote.ownership(item, owner ? "RELEASED" : "CLAIMED", null, null));
                        actions.getChildren().add(claim);
                    }
                    if (historical == null && staff.role().equals("SUPERVISOR")
                            && (item.status() == Status.RECEIVED || item.status() == Status.PREPARING)) {
                        var reassign = new Button("Reassign item");
                        reassign.setDisable(!remote.writesAvailable() || item.station() == null);
                        reassign.setOnAction(event -> reassign(remote, item));
                        actions.getChildren().add(reassign);
                    }
                    row.getChildren().add(actions);
                }
            }
            if (historical == null && item.server() != null && item.status() == Status.RECEIVED
                    && item.server().allergyReviewStatus().equals("PENDING_ALLERGY_REVIEW")) {
                Button approve = new Button("Approve preparation");
                Button decline = new Button("Decline item");
                boolean canReview = service.writesAvailable() && staff != null && staff.allergyReviewer();
                approve.setDisable(!canReview);
                decline.setDisable(!canReview);
                approve.setOnAction(event -> promptReview(ticketId, item, true));
                decline.setOnAction(event -> promptReview(ticketId, item, false));
                row.getChildren().add(new HBox(8, approve, decline));
            }
            row.getStyleClass().add("detail-item");
            content.getChildren().add(row);
        }
    }

    static void showHistory(SpringBootKitchenService remote, Ticket.Item item, javafx.scene.Node owner) {
        var requestedBy = remote.signedInStaff();
        remote.preparationDetails(item, false).whenComplete((result, error) -> javafx.application.Platform.runLater(() -> {
            if (requestedBy != remote.signedInStaff() || owner.getScene() == null) return;
            var entries = new VBox(8);
            if (error != null) entries.getChildren().add(label("Preparation history could not be loaded.", "metadata"));
            else for (var element : result.getAsJsonArray("events")) {
                var entry = element.getAsJsonObject();
                String reason = value(entry, "reason", "");
                entries.getChildren().add(label(value(entry, "action", "") + " · " + value(entry, "actor", "Unknown")
                        + " · " + value(entry, "occurredAt", "") + " · v" + value(entry, "version", "")
                        + "\n" + value(entry, "previousStatus", "") + " → " + value(entry, "nextStatus", "")
                        + " · " + value(entry, "previousChef", "Unassigned") + " → " + value(entry, "nextChef", "Unassigned")
                        + (reason.isEmpty() ? "" : "\n" + reason), "metadata"));
            }
            if (entries.getChildren().isEmpty()) entries.getChildren().add(label("No preparation history recorded.", "metadata"));
            var dialog = new javafx.scene.control.Dialog<Void>();
            dialog.initOwner(owner.getScene().getWindow());
            dialog.setTitle("Preparation history");
            var scroll = new ScrollPane(entries);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportWidth(500);
            scroll.setPrefViewportHeight(275);
            dialog.getDialogPane().setContent(scroll);
            dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
            Runnable unsubscribe = remote.onOrdersChanged(() -> {
                if (requestedBy != remote.signedInStaff()) dialog.close();
            });
            dialog.setOnHidden(event -> unsubscribe.run());
            dialog.show();
        }));
    }

    private record ChefChoice(java.util.UUID id, String username) {
        @Override public String toString() { return username; }
    }

    private void reassign(SpringBootKitchenService remote, Ticket.Item item) {
        var requestedBy = remote.signedInStaff();
        remote.preparationDetails(item, true).whenComplete((result, error) -> javafx.application.Platform.runLater(() -> {
            if (requestedBy != remote.signedInStaff() || getScene() == null) return;
            var chef = new javafx.scene.control.ComboBox<ChefChoice>();
            if (error == null) for (var element : result.getAsJsonArray("chefs")) {
                var record = element.getAsJsonObject();
                chef.getItems().add(new ChefChoice(java.util.UUID.fromString(value(record, "id", "")), value(record, "username", "")));
            }
            if (chef.getItems().isEmpty()) {
                var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                        error == null ? "No other eligible Chef is available for this station." : "Eligible Chefs could not be loaded.");
                alert.initOwner(getScene().getWindow());
                alert.show();
                return;
            }
            chef.setPromptText("Choose an eligible Chef");
            var reason = new javafx.scene.control.TextField();
            reason.setPromptText("Reason for reassignment");
            var dialog = new javafx.scene.control.Dialog<javafx.scene.control.ButtonType>();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle("Reassign item");
            dialog.getDialogPane().setContent(new VBox(8, chef, reason));
            dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK).disableProperty().bind(
                    javafx.beans.binding.Bindings.createBooleanBinding(() -> chef.getValue() == null
                            || reason.getText().isBlank() || reason.getText().length() > 240, chef.valueProperty(), reason.textProperty()));
            if (dialog.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK && requestedBy == remote.signedInStaff()) {
                remote.ownership(item, "REASSIGNED", chef.getValue().id(), reason.getText().strip());
            }
        }));
    }

    private static String value(com.google.gson.JsonObject entry, String field, String fallback) {
        var element = entry.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private void promptReview(long ticketId, Ticket.Item item, boolean approve) {
        var dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Chef allergy review");
        dialog.setHeaderText(approve ? "Record the verified preparation controls" : "Record why this item cannot be prepared");
        dialog.setContentText("Private reason");
        var submit = dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        submit.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            String reason = dialog.getEditor().getText().strip();
            return reason.isBlank() || reason.length() > 240;
        }, dialog.getEditor().textProperty()));
        dialog.showAndWait().ifPresent(reason -> service.reviewAllergy(ticketId, item, approve, reason.strip()));
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
