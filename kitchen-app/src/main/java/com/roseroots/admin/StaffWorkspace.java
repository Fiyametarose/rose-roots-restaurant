package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class StaffWorkspace extends VBox {
    private final AdminApi api;
    private final Runnable expired;
    private final VBox accountList = new VBox(6);
    private final Label status = new Label();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final ComboBox<String> role = new ComboBox<>();
    private final CheckBox review = new CheckBox("Qualified for allergy review");
    private final Button create = new Button("Create account");
    private final Button refresh = new Button("Refresh staff");
    private final TextField issued = new TextField();
    private final VBox workstationRows = new VBox(6);
    private final TextField workstationLabel = new TextField();
    private final Button issueWorkstation = new Button("Issue workstation credential");
    private final Button copyWorkstation = new Button("Copy new workstation credential once");
    private String workstationToken;

    StaffWorkspace(AdminApi api, Runnable expired) {
        super(10);
        this.api = api;
        this.expired = expired;
        var heading = new Label("Staff & Security");
        heading.getStyleClass().add("heading");
        username.setPromptText("Username");
        password.setPromptText("Manager password");
        role.getItems().addAll("CHEF", "SUPERVISOR", "WAITER", "MANAGER");
        role.getSelectionModel().selectFirst();
        role.setOnAction(event -> configureRole());
        create.setOnAction(event -> create());
        refresh.setOnAction(event -> load());
        issued.setEditable(false);
        issued.setAccessibleText("New Kitchen bearer credential");
        var clear = new Button("Hide credential");
        var credential = new VBox(5, new Label("Copy this one-time credential into the assigned staff launch environment."), issued, clear);
        credential.visibleProperty().bind(issued.textProperty().isNotEmpty());
        credential.managedProperty().bind(credential.visibleProperty());
        clear.setOnAction(event -> issued.clear());
        getChildren().addAll(heading, new FlowPane(8, 5, username, role, review, password, create), credential,
                refresh, status, accountList, new Label("Shared Kitchen workstations"),
                new FlowPane(8, 5, workstationLabel, issueWorkstation, copyWorkstation), workstationRows);
        workstationLabel.setPromptText("Workstation label");
        issueWorkstation.setOnAction(event -> {
            clearWorkstationToken();
            String label = workstationLabel.getText();
            run(() -> api.issueWorkstation(label), result -> {
                workstationToken = result.get("bearerToken").getAsString();
                copyWorkstation.setVisible(true);
                workstationLabel.clear();
                load();
            });
        });
        copyWorkstation.managedProperty().bind(copyWorkstation.visibleProperty());
        clearWorkstationToken();
        copyWorkstation.setOnAction(event -> {
            if (workstationToken == null) return;
            var content = new ClipboardContent();
            content.putString(workstationToken);
            if (Clipboard.getSystemClipboard().setContent(content)) {
                clearWorkstationToken();
                status.setText("Credential copied. Save it to the assigned workstation's protected credential file and clear the clipboard.");
            }
        });
        configureRole();
        load();
    }

    private void configureRole() {
        boolean manager = "MANAGER".equals(role.getValue());
        password.setVisible(manager);
        password.setManaged(manager);
        if (!manager) password.clear();
        boolean chef = "CHEF".equals(role.getValue());
        review.setDisable(!chef);
        if (!chef) review.setSelected(false);
    }

    private void create() {
        var body = new JsonObject();
        body.addProperty("username", username.getText());
        body.addProperty("role", role.getValue());
        body.addProperty("allergyReviewer", review.isSelected());
        if (password.isVisible()) body.addProperty("password", password.getText());
        password.clear();
        issued.clear();
        run(() -> api.saveStaff(null, body), result -> {
            username.clear();
            var token = result.get("bearerToken");
            if (token != null && !token.isJsonNull()) issued.setText(token.getAsString());
            load();
        });
    }

    private void load() {
        run(api::staff, accounts -> {
            accountList.getChildren().clear();
            accounts.forEach(value -> accountList.getChildren().add(row(value.getAsJsonObject())));
            run(api::workstations, workstations -> {
                workstationRows.getChildren().clear();
                workstations.forEach(value -> workstationRows.getChildren().add(workstationRow(value.getAsJsonObject())));
                if (workstations.isEmpty()) workstationRows.getChildren().add(new Label("No shared workstations have been provisioned."));
            });
        });
    }

    private FlowPane workstationRow(JsonObject workstation) {
        boolean revoked = workstation.has("revokedAt") && !workstation.get("revokedAt").isJsonNull();
        String expiry = workstation.get("expiresAt").getAsString();
        var revoke = new Button("Revoke");
        revoke.setDisable(revoked);
        revoke.setOnAction(event -> {
            var confirmation = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "Revoke access for " + workstation.get("label").getAsString() + "?");
            confirmation.initOwner(getScene().getWindow());
            if (confirmation.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) return;
            clearWorkstationToken();
            run(() -> {
                api.revokeWorkstation(workstation.get("id").getAsString());
                return null;
            }, result -> load());
        });
        return new FlowPane(8, 5, new Label(workstation.get("label").getAsString()
                + (revoked ? " · Revoked" : " · Expires " + expiry)), revoke);
    }

    private void clearWorkstationToken() {
        workstationToken = null;
        copyWorkstation.setVisible(false);
    }

    private FlowPane row(JsonObject account) {
        var enabled = new CheckBox("Enabled");
        enabled.setSelected(account.get("enabled").getAsBoolean());
        var allergy = new CheckBox("Allergy review");
        allergy.setSelected(account.get("allergyReviewer").getAsBoolean());
        String accountRole = account.get("role").getAsString();
        allergy.setDisable(!"CHEF".equals(accountRole));
        var save = new Button("Save permissions");
        save.setOnAction(event -> {
            var changes = new JsonObject();
            changes.addProperty("expectedVersion", account.get("version").getAsLong());
            changes.addProperty("enabled", enabled.isSelected());
            changes.addProperty("allergyReviewer", allergy.isSelected());
            run(() -> api.saveStaff(account.get("id").getAsString(), changes), result -> load());
        });
        var controls = new FlowPane(8, 5, new Label(account.get("username").getAsString() + " · " + accountRole), enabled, allergy, save);
        if (!"MANAGER".equals(accountRole)) {
            var reset = new Button("Set Kitchen password");
            reset.setOnAction(event -> setKitchenPassword(account));
            controls.getChildren().add(reset);
        }
        return controls;
    }

    private void setKitchenPassword(JsonObject account) {
        var dialog = new javafx.scene.control.Dialog<javafx.scene.control.ButtonType>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Set Kitchen password");
        var secret = new PasswordField();
        secret.setPromptText("New password (12 characters minimum)");
        dialog.getDialogPane().setContent(new VBox(6, new Label("This ends existing sessions and retires the previous bearer credential."), secret));
        var confirm = new javafx.scene.control.ButtonType("Set password", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(confirm).disableProperty().bind(secret.textProperty().length().lessThan(12));
        if (dialog.showAndWait().orElse(null) == confirm) {
            var changes = new JsonObject();
            changes.addProperty("expectedVersion", account.get("version").getAsLong());
            changes.addProperty("enabled", account.get("enabled").getAsBoolean());
            changes.addProperty("allergyReviewer", account.get("allergyReviewer").getAsBoolean());
            changes.addProperty("kitchenPassword", secret.getText());
            run(() -> api.saveStaff(account.get("id").getAsString(), changes), result -> load());
        }
        secret.clear();
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        create.setDisable(true);
        refresh.setDisable(true);
        accountList.setDisable(true);
        workstationRows.setDisable(true);
        issueWorkstation.setDisable(true);
        status.setText("Contacting the staff service…");
        AdminWorkspace.background(task, (result, error) -> {
            create.setDisable(false);
            refresh.setDisable(false);
            accountList.setDisable(false);
            workstationRows.setDisable(false);
            issueWorkstation.setDisable(false);
            if (error == null) {
                status.setText("");
                done.accept(result);
            } else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                issued.clear();
                clearWorkstationToken();
                expired.run();
            } else {
                status.setText(error instanceof AdminApi.Rejected rejected && rejected.status == 409
                        ? "The change could not be saved. Refresh staff and check the account or restaurant setup."
                        : "The request failed. Check the fields or refresh staff before retrying.");
            }
        });
    }
}
