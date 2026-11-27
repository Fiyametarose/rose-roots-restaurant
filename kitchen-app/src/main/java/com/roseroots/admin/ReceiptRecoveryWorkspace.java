package com.roseroots.admin;

import com.roseroots.kitchen.components.StaffDialogs;

import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import static com.roseroots.admin.CatalogControls.text;

final class ReceiptRecoveryWorkspace extends Dialog<Void> {
    private record Terminal(String id, String name) {
        @Override public String toString() { return name + " (" + id + ")"; }
    }

    ReceiptRecoveryWorkspace(Window owner, AdminApi api, String receipt, String visit) {
        initOwner(owner);
        setTitle("Authorize recovery of a verified cash receipt");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var target = new ComboBox<Terminal>();
        target.setPromptText("Verified receiving terminal");
        target.setMaxWidth(Double.MAX_VALUE);
        var verified = new CheckBox("I verified the claimant and the receiving terminal in person");
        var issue = new Button("Issue five-minute recovery code");
        var revoke = new Button("Revoke recovery code");
        revoke.setVisible(false);
        revoke.managedProperty().bind(revoke.visibleProperty());
        var code = new TextField();
        code.setEditable(false);
        code.setVisible(false);
        code.managedProperty().bind(code.visibleProperty());
        var status = new Label("Loading registered terminals…");
        status.setWrapText(true);
        issue.disableProperty().bind(target.valueProperty().isNull().or(verified.selectedProperty().not())
                .or(target.disableProperty()));
        var details = new Label("Receipt " + receipt + "\nVisit " + visit);
        details.setWrapText(true);
        var content = new VBox(10, details, target, verified, issue, status, code, revoke);
        content.setPadding(new Insets(14));
        content.setPrefWidth(540);
        getDialogPane().setContent(content);
        StaffDialogs.theme(this);
        var close = getDialogPane().lookupButton(ButtonType.CLOSE);
        AdminWorkspace.background(() -> api.adminRows("terminals"), (terminals, failure) -> {
            if (!isShowing()) return;
            if (failure != null) {
                status.setText("Could not load terminals. Check the Manager session and connection.");
                return;
            }
            terminals.forEach(row -> {
                var terminal = row.getAsJsonObject();
                if (terminal.get("enabled").getAsBoolean()) {
                    target.getItems().add(new Terminal(text(terminal, "id"), text(terminal, "label")));
                }
            });
            status.setText(target.getItems().isEmpty() ? "No enabled receiving terminals." : "");
        });
        issue.setOnAction(event -> {
            var input = new JsonObject();
            input.addProperty("sessionId", visit);
            input.addProperty("terminalId", target.getValue().id());
            target.setDisable(true);
            verified.setDisable(true);
            close.setDisable(true);
            status.setText("Issuing recovery code…");
            AdminWorkspace.background(() -> api.adminWrite("receipts/" + receipt + "/recovery", "POST", input, 201),
                    (grant, failure) -> {
                        if (!isShowing()) return;
                        close.setDisable(false);
                        if (failure != null) {
                            target.setDisable(false);
                            verified.setDisable(false);
                            status.setText("Could not issue a code. Verify the selected visit and terminal.");
                            return;
                        }
                        code.setText(text(grant, "code"));
                        String grantId = text(grant, "grantId");
                        code.setVisible(true);
                        revoke.setVisible(true);
                        status.setText("Give the code directly to the verified claimant for entry on this terminal. "
                                + "It expires in five minutes and is shown here only.");
                        revoke.setOnAction(revokeEvent -> {
                            revoke.setDisable(true);
                            AdminWorkspace.background(() -> {
                                api.revokeReceiptRecovery(grantId);
                                return true;
                            }, (done, error) -> {
                                if (error == null) {
                                    code.clear();
                                    code.setVisible(false);
                                    revoke.setVisible(false);
                                    status.setText("Recovery code revoked.");
                                } else {
                                    revoke.setDisable(false);
                                    status.setText("Could not revoke the code. It may already have been used.");
                                }
                            });
                        });
                    });
        });
        setOnHidden(event -> code.clear());
    }
}
