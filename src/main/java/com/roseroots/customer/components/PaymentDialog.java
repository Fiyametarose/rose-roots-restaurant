package com.roseroots.customer.components;

import com.roseroots.customer.models.Payment;
import com.roseroots.customer.services.OrderService.Problem;
import com.roseroots.customer.state.PaymentCheckout;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

public final class PaymentDialog extends Dialog<Void> {
    private final Locale locale;
    private final Messages messages;
    private final PaymentCheckout checkout;
    private final Label balance;
    private final Label notice;
    private final Label status;
    private final VBox receipt = new VBox(8);
    private final CheckBox cash;
    private final Button start;
    private final Button retry;
    private final Button cancel;
    private final Timeline poll;
    private PaymentCheckout.View current;
    private boolean busy;
    private boolean needsRefresh;
    private int generation;

    public PaymentDialog(Window owner, Locale locale, String context, PaymentCheckout checkout) {
        this.locale = locale;
        this.checkout = checkout;
        messages = new Messages(locale);
        initOwner(owner);
        setTitle(messages.text("pay.title"));
        var pane = getDialogPane();
        pane.getStyleClass().addAll("assistance-dialog", "payment-dialog");
        if ("am".equals(messages.language())) pane.getStyleClass().add("ethiopic");
        messages.applyDirection(pane);
        var css = getClass().getResource("/styles/theme.css");
        pane.getStylesheets().add(css.toExternalForm());
        if (owner.getScene().getRoot().getStyle() != null) pane.setStyle(owner.getScene().getRoot().getStyle());
        var close = new ButtonType(messages.text("pay.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().add(close);
        pane.lookupButton(close).getStyleClass().addAll("catalog-action", "action-secondary");
        pane.setPrefWidth(Math.min(640, Math.max(300, owner.getWidth() - 56)));

        var title = label("pay.title", "dish-panel-title");
        balance = label("pay.loading", "dish-description");
        balance.setId("payment-balance");
        status = FoodCard.label("", "assistance-result");
        status.setId("payment-status");
        notice = FoodCard.label("", "catalog-muted");
        cash = new CheckBox(messages.text("pay.cash"));
        cash.setId("payment-cash");
        cash.setMinHeight(46);
        cash.selectedProperty().addListener((property, previous, chosen) -> updateActions());
        start = CustomerShell.action(messages.text("pay.start"), true, () -> perform(() -> checkout.start(current.balance()), false));
        start.setId("payment-start");
        retry = CustomerShell.action(messages.text("pay.refresh"), false, () -> perform(() -> checkout.recover(true), false));
        retry.setId("payment-refresh");
        cancel = CustomerShell.action(messages.text("pay.cancel"), false, () -> perform(() -> checkout.cancel(current.attempt()), false));
        cancel.setId("payment-cancel");
        var actions = new FlowPane(8, 8, start, cancel, retry);
        var body = new VBox(10, title, FoodCard.label(context, "catalog-muted"), balance, status, notice,
                cash, actions, receipt);
        body.getStyleClass().add("assistance-body");
        var scroll = new ScrollPane(body);
        scroll.getStyleClass().add("payment-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(400, Math.max(225, owner.getHeight() - 145)));
        pane.setContent(scroll);

        poll = new Timeline(new KeyFrame(Duration.seconds(4), event -> perform(checkout::refresh, true)));
        poll.setCycleCount(Timeline.INDEFINITE);
        setOnShown(event -> {
            perform(checkout::refresh, false);
            poll.playFromStart();
        });
        setOnHidden(event -> {
            generation++;
            poll.stop();
            busy = false;
        });
        updateActions();
    }

    private void perform(Supplier<CompletionStage<PaymentCheckout.View>> request, boolean background) {
        if (busy) return;
        busy = true;
        int active = generation;
        if (!background) {
            notice.setText(messages.text("pay.loading"));
            updateActions();
        }
        CompletableFuture.completedFuture(request).thenComposeAsync(Supplier::get)
                .whenComplete((view, failure) -> Platform.runLater(() -> {
                    if (!isShowing() || active != generation) return;
                    busy = false;
                    if (failure == null) render(view);
                    else {
                        needsRefresh = true;
                        cash.setSelected(false);
                        var error = PaymentCheckout.root(failure);
                        var code = error instanceof Problem problem ? problem.code() : "";
                        notice.setText(messages.text(switch (code) {
                            case "BILL_VERSION_CONFLICT" -> "pay.changed";
                            case "PAYMENT_OUTCOME_UNKNOWN", "CONNECTION_INTERRUPTED" -> "pay.uncertain";
                            case "IDEMPOTENCY_CONFLICT" -> "pay.conflict";
                            default -> "pay.unavailable";
                        }));
                    }
                    updateActions();
                }));
    }

    private void render(PaymentCheckout.View view) {
        var prior = current;
        current = view;
        needsRefresh = false;
        var value = view.balance();
        if (prior == null || prior.balance().billVersion() != value.billVersion() || !value.canStart()) cash.setSelected(false);
        var numbers = java.text.NumberFormat.getIntegerInstance(locale);
        balance.setText(messages.text("pay.payable") + " " + money(value.payable()) + "\n"
                + messages.text("pay.outstanding") + " " + money(value.outstanding()) + "\n"
                + messages.text("pay.version") + " " + numbers.format(value.billVersion()));
        var attempt = view.attempt();
        status.setText(attempt == null ? "" : messages.text(switch (attempt.status()) {
            case PENDING -> attempt.cancellationRequested() ? "pay.cancelRequested" : "pay.pending";
            case SUCCEEDED -> "pay.succeeded";
            case FAILED -> "pay.failed";
            case CANCELLED -> "pay.cancelled";
        }));
        if (view.receiptUnavailable()) notice.setText(messages.text("pay.receiptUnavailable"));
        else if (value.blocked().contains("CHEF_DECISION_PENDING")) notice.setText(messages.text("pay.chefHold"));
        else if (value.blocked().contains("SESSION_NOT_OPEN") && attempt == null) notice.setText(messages.text("pay.closed"));
        else if (value.blocked().contains("ALREADY_SETTLED") && attempt == null) notice.setText(messages.text("pay.noReceiptReference"));
        else if (value.blocked().contains("NOTHING_DUE")) notice.setText(messages.text("pay.nothingDue"));
        else if (!value.cashAvailable() && attempt == null) notice.setText(messages.text("pay.unavailable"));
        else notice.setText("");
        if (prior == null || !java.util.Objects.equals(prior.receipt(), view.receipt())) renderReceipt(view.receipt());
    }

    private void renderReceipt(Payment.Receipt value) {
        receipt.getChildren().clear();
        if (value == null) return;
        var title = label("pay.receipt", "dish-section-heading");
        title.setId("verified-receipt");
        receipt.getChildren().addAll(title,
                FoodCard.label(value.id().toString(), "catalog-muted"),
                FoodCard.label(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
                        .withZone(ZoneId.systemDefault()).format(value.verifiedAt()), "catalog-muted"));
        var numbers = java.text.NumberFormat.getIntegerInstance(locale);
        for (var line : value.lines()) {
            var price = line.price();
            receipt.getChildren().add(FoodCard.label(numbers.format(price.quantity()) + " × " + price.name()
                    + "   " + money(price.extendedMinor()), "dish-description"));
            for (var option : price.options()) {
                receipt.getChildren().add(FoodCard.label(option.name() + "   " + money(option.unitAdjustmentMinor()),
                        "catalog-muted"));
            }
        }
        receipt.getChildren().add(FoodCard.label(messages.text("pay.settled") + " " + money(value.amount()), "dish-section-heading"));
    }

    private void updateActions() {
        var attempt = current == null ? null : current.attempt();
        boolean pending = attempt != null && attempt.status() == Payment.Status.PENDING;
        boolean selectable = current != null && !needsRefresh && current.balance().canStart()
                && !pending && (attempt == null || attempt.status() != Payment.Status.SUCCEEDED);
        show(cash, selectable);
        show(start, selectable);
        show(cancel, pending && !attempt.cancellationRequested());
        start.setDisable(busy || !cash.isSelected());
        cancel.setDisable(busy || needsRefresh);
        retry.setDisable(busy);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private String money(long minor) { return MenuText.price(BigDecimal.valueOf(minor, 2), Currency.getInstance("KES"), locale); }
    private Label label(String key, String style) { return FoodCard.label(messages.text(key), style); }
}
