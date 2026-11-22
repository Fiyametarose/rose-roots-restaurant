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

    StaffWorkspace(AdminApi api, Runnable expired) {
        super(10);
        this.api = api;
        this.expired = expired;
        var heading = new Label("Staff & Security");
        heading.getStyleClass().add("heading");
        username.setPromptText("Username");
        password.setPromptText("Manager password");
        role.getItems().addAll("CHEF", "SUPERVISOR", "MANAGER");
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
                refresh, status, accountList);
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
            if (accounts.isEmpty()) status.setText("No managed staff accounts have been created.");
        });
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
        return new FlowPane(8, 5, new Label(account.get("username").getAsString() + " · " + accountRole), enabled, allergy, save);
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        create.setDisable(true);
        refresh.setDisable(true);
        accountList.setDisable(true);
        status.setText("Contacting the staff service…");
        AdminWorkspace.background(task, (result, error) -> {
            create.setDisable(false);
            refresh.setDisable(false);
            accountList.setDisable(false);
            if (error == null) {
                status.setText("");
                done.accept(result);
            } else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                issued.clear();
                expired.run();
            } else {
                status.setText(error instanceof AdminApi.Rejected rejected && rejected.status == 409
                        ? "The account could not be saved. Check the username or refresh staff before retrying."
                        : "The staff request failed. Check the account fields or refresh staff before retrying.");
            }
        });
    }
}
