package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class StaffWorkspace extends VBox {
    private final AdminApi api;
    private final Runnable expired;
    private final VBox accountList = new VBox(6);
    private final VBox archivedList = new VBox(6);
    private final Label status = new Label();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final ComboBox<String> role = new ComboBox<>();
    private final Button create = new Button("Create account");
    private final Button refresh = new Button("Refresh staff");
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
        password.setPromptText("Staff password (12 characters minimum)");
        role.getItems().addAll("CHEF", "SUPERVISOR", "WAITER", "MANAGER");
        role.getSelectionModel().selectFirst();
        create.setOnAction(event -> create());
        refresh.setOnAction(event -> load());
        var accounts = new TabPane(new Tab("Current accounts", accountList), new Tab("Archived accounts", archivedList));
        accounts.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getChildren().addAll(heading, new FlowPane(8, 5, username, role, password, create),
                refresh, status, accounts, new Label("Shared Kitchen workstations"),
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
        load();
    }

    private void create() {
        var body = new JsonObject();
        body.addProperty("username", username.getText());
        body.addProperty("role", role.getValue());
        body.addProperty("password", password.getText());
        password.clear();
        run(() -> api.saveStaff(null, body), result -> {
            username.clear();
            load();
        });
    }

    private void load() {
        run(api::staff, accounts -> {
            accountList.getChildren().clear();
            archivedList.getChildren().clear();
            accounts.forEach(value -> {
                var account = value.getAsJsonObject();
                var destination = "ARCHIVED".equals(account.get("state").getAsString()) ? archivedList : accountList;
                destination.getChildren().add(row(account));
            });
            if (accountList.getChildren().isEmpty()) accountList.getChildren().add(new Label("No current staff accounts."));
            if (archivedList.getChildren().isEmpty()) archivedList.getChildren().add(new Label("No archived staff accounts."));
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
        String state = account.get("state").getAsString();
        String label = switch (state) {
            case "ACTIVE" -> account.get("needsPasswordSetup").getAsBoolean() ? "Active · Password setup required" : "Active";
            case "ARCHIVED" -> "Archived";
            default -> "Deactivated";
        };
        var controls = new FlowPane(8, 5, new Label(account.get("username").getAsString() + " · "
                + account.get("role").getAsString()), new Label(label));
        if ("ARCHIVED".equals(state)) {
            controls.getChildren().add(transition(account, "Restore", "DEACTIVATED",
                    "Restore the account to the current list? It will remain deactivated until a new password is set."));
            return controls;
        }
        boolean active = "ACTIVE".equals(state);
        var passwordAction = new Button(active ? "Set staff password" : "Activate with new password");
        passwordAction.setOnAction(event -> setPassword(account, active));
        controls.getChildren().add(passwordAction);
        if (active) controls.getChildren().add(transition(account, "Deactivate", "DEACTIVATED",
                "Deactivate this account and revoke its access?"));
        controls.getChildren().add(transition(account, "Archive", "ARCHIVED",
                "Archive this account and revoke its access? Historical activity will be retained."));
        return controls;
    }

    private Button transition(JsonObject account, String action, String state, String message) {
        var button = new Button(action);
        button.setOnAction(event -> {
            var confirmation = new Alert(Alert.AlertType.CONFIRMATION, message);
            confirmation.initOwner(getScene().getWindow());
            confirmation.setHeaderText(account.get("username").getAsString());
            if (confirmation.showAndWait().orElse(null) == ButtonType.OK) save(account, state, null);
        });
        return button;
    }

    private void setPassword(JsonObject account, boolean active) {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(active ? "Set staff password" : "Activate staff account");
        var secret = new PasswordField();
        secret.setPromptText("New password (12 characters minimum)");
        dialog.getDialogPane().setContent(new VBox(6, new Label("This ends existing sessions and retires the previous bearer credential."), secret));
        var confirm = new ButtonType(active ? "Set password" : "Activate", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(confirm).disableProperty().bind(secret.textProperty().length().lessThan(12));
        if (dialog.showAndWait().orElse(null) == confirm) {
            save(account, "ACTIVE", secret.getText());
        }
        secret.clear();
    }

    private void save(JsonObject account, String state, String password) {
        var changes = new JsonObject();
        changes.addProperty("expectedVersion", account.get("version").getAsLong());
        changes.addProperty("state", state);
        if (password != null) changes.addProperty("password", password);
        run(() -> api.saveStaff(account.get("id").getAsString(), changes), result -> load());
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        create.setDisable(true);
        refresh.setDisable(true);
        accountList.setDisable(true);
        archivedList.setDisable(true);
        workstationRows.setDisable(true);
        issueWorkstation.setDisable(true);
        status.setText("Contacting the staff service…");
        AdminWorkspace.background(task, (result, error) -> {
            create.setDisable(false);
            refresh.setDisable(false);
            accountList.setDisable(false);
            archivedList.setDisable(false);
            workstationRows.setDisable(false);
            issueWorkstation.setDisable(false);
            if (error == null) {
                status.setText("");
                done.accept(result);
            } else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                clearWorkstationToken();
                expired.run();
            } else {
                status.setText(error instanceof AdminApi.Rejected rejected && !rejected.detail.isBlank()
                        ? rejected.detail : error instanceof AdminApi.Rejected rejected && rejected.status == 409
                        ? "The change could not be saved. Refresh staff and check the account or restaurant setup."
                        : "The request failed. Check the fields or refresh staff before retrying.");
            }
        });
    }
}
