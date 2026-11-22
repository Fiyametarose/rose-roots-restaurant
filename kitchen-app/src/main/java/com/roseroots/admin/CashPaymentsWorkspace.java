package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class CashPaymentsWorkspace extends VBox {
    private final AdminApi api;
    private final Runnable expired;
    private final Button refresh = new Button("Refresh cash requests");
    private final Label status = new Label();
    private final VBox requests = new VBox(8);

    CashPaymentsWorkspace(AdminApi api, Runnable expired) {
        super(10);
        this.api = api;
        this.expired = expired;
        refresh.setOnAction(event -> load());
        getChildren().addAll(refresh, status, requests);
        load();
    }

    private void load() {
        run(api::cashRequests, data -> {
            requests.getChildren().clear();
            data.forEach(entry -> requests.getChildren().add(row(entry.getAsJsonObject())));
            if (data.isEmpty()) status.setText("No pending cash requests.");
        });
    }

    private VBox row(JsonObject request) {
        String id = request.get("id").getAsString();
        String session = request.get("sessionId").getAsString();
        String amount = "KSh " + BigDecimal.valueOf(request.get("amountMinor").getAsLong(), 2).toPlainString();
        boolean cancellation = request.get("cancellationRequested").getAsBoolean();
        var confirm = new Button("Confirm collected cash");
        confirm.setOnAction(event -> confirm(request, amount));
        var failed = new Button(cancellation ? "Resolve cancellation" : "Record failed collection");
        failed.setOnAction(event -> resolve(request, cancellation));
        var info = new Label(cancellation ? "Cancellation requested. Verify whether cash was collected." : "Awaiting cash collection");
        return new VBox(5, new Label(amount + " · Payment " + id), new Label("Visit " + session), info,
                new FlowPane(8, 5, confirm, failed));
    }

    private void confirm(JsonObject request, String amount) {
        var prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "Confirm only after you collected " + amount + " for this exact payment and returned any change.",
                ButtonType.CANCEL, ButtonType.OK);
        prompt.initOwner(getScene().getWindow());
        prompt.setHeaderText("Confirm collected cash");
        prompt.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
            var body = version(request);
            body.addProperty("expectedBillVersion", request.get("billVersion").getAsLong());
            run(() -> api.confirmCash(request.get("id").getAsString(), body), saved -> load());
        });
    }

    private void resolve(JsonObject request, boolean cancellation) {
        var prompt = new TextInputDialog();
        prompt.initOwner(getScene().getWindow());
        prompt.setHeaderText("Verify no cash was collected and record a reason");
        prompt.showAndWait().map(String::strip).ifPresent(reason -> {
            if (reason.isEmpty() || reason.length() > 240) {
                status.setText("Enter a reason of 1–240 characters.");
                return;
            }
            var body = version(request);
            body.addProperty("status", cancellation ? "CANCELLED" : "FAILED");
            body.addProperty("reason", reason);
            run(() -> api.resolveCash(request.get("id").getAsString(), body), saved -> load());
        });
    }

    private static JsonObject version(JsonObject entry) {
        var body = new JsonObject();
        body.addProperty("expectedVersion", entry.get("version").getAsLong());
        return body;
    }

    private <T> void run(Callable<T> task, Consumer<T> done) {
        refresh.setDisable(true);
        requests.setDisable(true);
        status.setText("Contacting payment service…");
        AdminWorkspace.background(task, (result, error) -> {
            refresh.setDisable(false);
            requests.setDisable(false);
            if (error == null) {
                status.setText("");
                done.accept(result);
            } else if (error instanceof AdminApi.Rejected rejected && (rejected.status == 401 || rejected.status == 403)) {
                expired.run();
            } else {
                status.setText("The payment action could not be confirmed. Refresh before retrying.");
            }
        });
    }
}
