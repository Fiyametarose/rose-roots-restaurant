package com.roseroots.admin;

import com.roseroots.kitchen.components.StaffDialogs;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class VisitsWorkspace extends VBox {
    private record Table(String id, String number, boolean enabled) {
        @Override public String toString() { return id == null ? "Unassigned" : "Table " + number + (enabled ? "" : " (disabled)"); }
    }

    private record Data(List<JsonObject> tables, List<JsonObject> terminals, List<JsonObject> sessions) {}

    private final AdminApi api;
    private final Runnable expired;
    private final Button refresh = new Button("Refresh tables and visits");
    private final Label status = new Label();
    private final VBox panels = new VBox(14);
    private final TextField credential = new TextField();

    VisitsWorkspace(AdminApi api, Runnable expired) {
        super(10);
        this.api = api;
        this.expired = expired;
        var heading = new Label("Tables & Dining Sessions");
        heading.getStyleClass().add("heading");
        credential.setEditable(false);
        credential.setAccessibleText("New terminal bearer credential");
        var hide = new Button("Hide terminal credential");
        hide.setOnAction(event -> credential.clear());
        var provisioned = new VBox(5, new Label("Store this one-time credential in the new terminal's token file."), credential, hide);
        provisioned.visibleProperty().bind(credential.textProperty().isNotEmpty());
        provisioned.managedProperty().bind(provisioned.visibleProperty());
        refresh.setOnAction(event -> load());
        getChildren().addAll(heading, refresh, status, provisioned, panels);
        load();
    }

    private void load() {
        run(() -> new Data(rows("tables"), rows("terminals"), rows("sessions")), data -> {
            var tableRows = new VBox(7);
            tableRows.getChildren().add(table(null));
            data.tables().forEach(row -> tableRows.getChildren().add(table(row)));
            var choices = data.tables().stream().map(row -> new Table(string(row,"id"), string(row,"displayNumber"),
                    row.get("enabled").getAsBoolean())).toList();
            var terminalRows = new VBox(7);
            terminalRows.getChildren().add(terminal(null, choices));
            data.terminals().forEach(row -> terminalRows.getChildren().add(terminal(row, choices)));
            var sessions = new VBox(7);
            data.sessions().forEach(row -> sessions.getChildren().add(visit(row)));
            if (data.sessions().isEmpty()) sessions.getChildren().add(new Label("No open or expired visits awaiting closure."));
            panels.getChildren().setAll(box("Dining sessions", sessions), box("Tables", tableRows), box("Terminals", terminalRows));
        });
    }

    private List<JsonObject> rows(String resource) throws java.io.IOException, InterruptedException {
        return api.adminRows(resource).asList().stream().map(row -> row.getAsJsonObject()).toList();
    }

    private Node table(JsonObject entry) {
        var number = new TextField(entry == null ? "" : string(entry, "displayNumber"));
        number.setPromptText("Table number");
        var enabled = new CheckBox("Enabled");
        enabled.setSelected(entry == null || entry.get("enabled").getAsBoolean());
        var save = new Button(entry == null ? "Register table" : "Save table");
        save.setOnAction(event -> {
            var input = new JsonObject();
            input.addProperty("displayNumber", number.getText());
            if (entry != null) { input.addProperty("enabled", enabled.isSelected()); version(input, entry); }
            write(entry == null ? "tables" : "tables/" + string(entry, "id"), entry == null ? "POST" : "PATCH", input);
        });
        return entry == null ? new FlowPane(8,5,number,save) : new FlowPane(8,5,number,enabled,save);
    }

    private Node terminal(JsonObject entry, List<Table> tables) {
        var name = new TextField(entry == null ? "" : string(entry, "label"));
        name.setPromptText("Terminal label");
        var assignment = new ComboBox<Table>();
        var unassigned = new Table(null, null, true);
        assignment.getItems().add(unassigned);
        String previous = entry == null || entry.get("tableId").isJsonNull() ? null : string(entry, "tableId");
        tables.stream().filter(table -> table.enabled() || table.id().equals(previous)).forEach(assignment.getItems()::add);
        assignment.setValue(tables.stream().filter(table -> table.id().equals(previous)).findFirst().orElse(unassigned));
        var enabled = new CheckBox("Enabled");
        enabled.setSelected(entry == null || entry.get("enabled").getAsBoolean());
        var save = new Button(entry == null ? "Register terminal" : "Save terminal");
        save.setOnAction(event -> {
            var input = new JsonObject();
            input.addProperty("label", name.getText());
            if (assignment.getValue().id() == null) input.add("tableId", JsonNull.INSTANCE);
            else input.addProperty("tableId", assignment.getValue().id());
            if (entry != null) { input.addProperty("enabled", enabled.isSelected()); version(input, entry); }
            write(entry == null ? "terminals" : "terminals/" + string(entry, "id"), entry == null ? "POST" : "PATCH", input);
        });
        return entry == null ? new FlowPane(8,5,name,assignment,save) : new FlowPane(8,5,name,assignment,enabled,save);
    }

    private Node visit(JsonObject row) {
        String reference = string(row, "serviceReference");
        long outstanding = row.get("outstandingMinor").getAsLong();
        boolean pending = row.get("paymentPending").getAsBoolean();
        var close = new Button("Close visit");
        close.setDisable(outstanding > 0 || pending);
        close.setOnAction(event -> {
            var prompt = new Alert(Alert.AlertType.CONFIRMATION, "Close the visit for " + reference + "?", ButtonType.CANCEL, ButtonType.OK);
            prompt.initOwner(getScene().getWindow());
            StaffDialogs.theme(prompt);
            prompt.setTitle("Close dining visit");
            prompt.showAndWait().filter(result -> result == ButtonType.OK).ifPresent(result -> {
                var input = new JsonObject();
                input.addProperty("expectedBillVersion", row.get("billVersion").getAsLong());
                run(() -> api.adminWrite("sessions/" + string(row,"id") + "/close", "POST", input, 200), saved -> load());
            });
        });
        String summary = reference + " · " + string(row,"status") + " · KSh " + BigDecimal.valueOf(outstanding,2).toPlainString();
        return new FlowPane(8,5,new Label(summary), new Label(pending ? "Payment pending" : ""), close);
    }

    private void write(String route, String method, JsonObject input) {
        credential.clear();
        run(() -> api.adminWrite(route, method, input, method.equals("POST") ? 201 : 200), saved -> {
            var token = saved.get("bearerToken");
            if (token != null && !token.isJsonNull()) credential.setText(token.getAsString());
            load();
        });
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        refresh.setDisable(true);
        panels.setDisable(true);
        status.setText("Contacting the visit service…");
        AdminWorkspace.background(task, (result, error) -> {
            refresh.setDisable(false);
            panels.setDisable(false);
            if (error == null) { status.setText(""); done.accept(result); }
            else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                credential.clear(); expired.run();
            } else if (error instanceof AdminApi.Rejected rejected && rejected.status == 409) {
                status.setText("This visit or device could not be changed. Check active visits, handoff, payment and the current version.");
            } else status.setText("The visit service could not complete that request. Check the values and refresh.");
        });
    }

    private static VBox box(String title, Node body) { return new VBox(8,new Label(title),body); }
    private static String string(JsonObject row, String key) { return row.get(key).getAsString(); }
    private static void version(JsonObject input, JsonObject entry) { input.addProperty("expectedVersion", entry.get("version").getAsLong()); }
}
