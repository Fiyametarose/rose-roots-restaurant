package com.roseroots.customer.components;

import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.Payment;
import com.roseroots.customer.services.OrderService.Problem;
import com.roseroots.customer.services.api.RestPaymentClient;
import com.roseroots.customer.state.PaymentCheckout;
import com.roseroots.customer.utils.Messages;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class ReceiptRecoveryDialog extends Dialog<Void> {
    private final Messages messages;
    private final Locale locale;
    private final Label status = new Label();
    private final VBox result = new VBox(8);
    private boolean busy;

    public ReceiptRecoveryDialog(Window owner, Locale locale, RestPaymentClient client) {
        this.locale = locale;
        messages = new Messages(locale);
        initOwner(owner);
        setTitle(messages.text("recovery.title"));
        var pane = getDialogPane();
        pane.getStyleClass().add("assistance-dialog");
        pane.getStylesheets().addAll(owner.getScene().getStylesheets());
        if ("am".equals(messages.language())) pane.getStyleClass().add("ethiopic");
        messages.applyDirection(pane);
        pane.getButtonTypes().add(new ButtonType(messages.text("pay.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        var visit = new TextField();
        visit.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        var visitLabel = new Label(messages.text("recovery.visit"));
        visitLabel.setLabelFor(visit);
        var lookup = new Button(messages.text("recovery.lookup"));
        lookup.setOnAction(event -> {
            UUID session;
            try {
                session = Ordering.canonicalId(visit.getText().strip());
            } catch (RuntimeException invalid) {
                status.setText(messages.text("recovery.unavailable"));
                return;
            }
            run(() -> PaymentCheckout.discoverReceipt(client, session), () -> {});
        });
        var code = new PasswordField();
        code.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        var codeLabel = new Label(messages.text("recovery.code"));
        codeLabel.setLabelFor(code);
        var redeem = new Button(messages.text("recovery.redeem"));
        var key = new java.util.concurrent.atomic.AtomicReference<>(UUID.randomUUID());
        code.textProperty().addListener((observable, before, after) -> key.set(UUID.randomUUID()));
        redeem.setOnAction(event -> {
            String entered = code.getText().strip();
            if (!entered.matches("[A-Za-z0-9_-]{43}")) {
                status.setText(messages.text("recovery.unavailable"));
                return;
            }
            UUID request = key.get();
            run(() -> client.redeem(entered, request), () -> {
                code.clear();
                client.completeRecovery(entered, request);
            });
        });
        status.setWrapText(true);
        var content = new VBox(8, visitLabel, visit, lookup, new Separator(), codeLabel, code, redeem, status, result);
        content.setPadding(new Insets(12));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(470);
        scroll.setPrefViewportHeight(460);
        pane.setContent(scroll);
        this.inputs = new Control[]{visit, lookup, code, redeem};
    }

    private final Control[] inputs;

    private void run(java.util.function.Supplier<CompletionStage<Payment.Receipt>> request, Runnable acknowledge) {
        if (busy) return;
        busy = true;
        for (var input : inputs) input.setDisable(true);
        result.getChildren().clear();
        status.setText(messages.text("pay.loading"));
        java.util.concurrent.CompletableFuture.completedFuture(request).thenCompose(java.util.function.Supplier::get)
                .whenComplete((receipt, failure) -> Platform.runLater(() -> {
            busy = false;
            for (var input : inputs) input.setDisable(false);
            if (!isShowing()) return;
            if (receipt == null || failure != null) {
                var cause = PaymentCheckout.root(failure);
                status.setText(messages.text(cause instanceof Problem problem
                        && java.util.Set.of("CONNECTION_INTERRUPTED", "SERVICE_UNAVAILABLE").contains(problem.code())
                        ? "recovery.disconnected" : "recovery.unavailable"));
                return;
            }
            status.setText("");
            PaymentDialog.renderReceipt(result, receipt, messages, locale);
            acknowledge.run();
        }));
    }
}
