package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class ManagerPriorityWorkspace extends VBox {
    private final AdminApi api;
    private final Runnable expired;
    private final VBox orders = new VBox(8);
    private final Button refresh = new Button("Refresh queue");
    private final Button next = new Button("Next page");
    private final Label status = new Label();
    private String cursor;

    ManagerPriorityWorkspace(AdminApi api, Runnable expired) {
        super(10);
        this.api = api;
        this.expired = expired;
        var heading = new Label("Kitchen priority management");
        heading.getStyleClass().add("heading");
        refresh.setOnAction(event -> load(null));
        next.setOnAction(event -> load(cursor));
        getChildren().addAll(heading, new FlowPane(8, 5, refresh, next), status, orders);
        load(null);
    }

    private void load(String requestedCursor) {
        run(() -> api.priorityQueue(requestedCursor), page -> {
            orders.getChildren().clear();
            page.getAsJsonArray("tickets").asList().stream().map(value -> value.getAsJsonObject())
                    .filter(ticket -> !"READY".equals(ticket.getAsJsonObject("order").get("status").getAsString()))
                    .forEach(ticket -> orders.getChildren().add(row(ticket)));
            var nextCursor = page.get("nextCursor");
            cursor = nextCursor.isJsonNull() ? null : nextCursor.getAsString();
            next.setDisable(cursor == null);
            if (orders.getChildren().isEmpty()) status.setText("No waiting or preparing orders on this page.");
        });
    }

    private FlowPane row(JsonObject ticket) {
        var order = ticket.getAsJsonObject("order");
        String id = order.get("id").getAsString();
        var reason = new TextField();
        reason.setPromptText("Reason for priority change");
        boolean rush = "RUSH".equals(ticket.get("priority").getAsString());
        boolean delayed = ticket.get("delayed").getAsBoolean();
        var rushButton = new Button(rush ? "Remove Rush" : "Apply Rush");
        var delayButton = new Button(delayed ? "Remove Delayed" : "Mark Delayed");
        rushButton.setOnAction(event -> save(order, "rush", !rush, reason.getText()));
        delayButton.setOnAction(event -> save(order, "delayed", !delayed, reason.getText()));
        return new FlowPane(8, 5, new Label(ticket.get("serviceDestination").getAsString() + " · " + id), reason,
                rushButton, delayButton);
    }

    private void save(JsonObject order, String marker, boolean enabled, String reason) {
        String detail = reason.strip();
        if (detail.isEmpty() || detail.length() > 240) {
            status.setText("Enter a reason of 1–240 characters.");
            return;
        }
        var body = new JsonObject();
        body.addProperty("expectedVersion", order.get("version").getAsLong());
        body.addProperty("reason", detail);
        if (marker.equals("rush")) body.addProperty("priority", enabled ? "RUSH" : "NORMAL");
        else body.addProperty("delayed", enabled);
        run(() -> api.changePriority(order.get("id").getAsString(), marker, body), result -> load(null));
    }

    private <T> void run(Callable<T> request, Consumer<T> done) {
        refresh.setDisable(true);
        next.setDisable(true);
        orders.setDisable(true);
        status.setText("Contacting the kitchen service…");
        AdminWorkspace.background(request, (result, error) -> {
            refresh.setDisable(false);
            next.setDisable(cursor == null);
            orders.setDisable(false);
            if (error == null) {
                status.setText("");
                done.accept(result);
            } else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                expired.run();
            } else {
                status.setText("The request could not be confirmed. Refresh the queue before retrying.");
            }
        });
    }
}
