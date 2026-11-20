package com.roseroots.customer.screens;

import com.roseroots.customer.components.CustomerShell;
import com.roseroots.customer.components.DishCustomizer;
import com.roseroots.customer.components.FoodCard;
import com.roseroots.customer.components.MenuPhoto;
import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.AllergyReview;
import com.roseroots.customer.models.CartPricing;
import com.roseroots.customer.models.ConfiguredDish;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.WaitEstimate;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.services.WaitEstimateService;
import com.roseroots.customer.state.CartDraft;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class CartScreen extends StackPane {
    private final RestaurantSettings settings;
    private final CustomerState state;
    private final WaitEstimateService estimates;
    private final Messages messages;
    private final CustomerShell shell;
    private final Consumer<UUID> edit;
    private final VBox lines = new VBox(10);
    private final VBox pricing = new VBox(7);
    private final Label waitTime;
    private final Label allergy;
    private final Button clear;
    private final PauseTransition expiry = new PauseTransition();
    private long estimateRevision;

    public CartScreen(RestaurantSettings settings, CustomerState state, WaitEstimateService estimates,
                      Consumer<Locale> language, Runnable home, Runnable menu, Consumer<UUID> edit,
                      Consumer<String> category) {
        this.settings = settings;
        this.state = state;
        this.estimates = estimates;
        this.edit = edit;
        messages = new Messages(state.locale());
        var overlay = new StackPane();
        overlay.setVisible(false);
        overlay.getStyleClass().add("filter-overlay");
        shell = new CustomerShell(settings, state, messages, language, home, null);
        shell.disableProperty().bind(overlay.visibleProperty());
        shell.displayCategories(state.menu().catalog(), state.locale(), category);

        var title = FoodCard.label(messages.text("cart.title"), "catalog-heading");
        var back = CustomerShell.action(messages.text("cart.continue"), false, menu);
        back.setId("cart-continue");
        var actions = new FlowPane(7, 5, back);
        clear = CustomerShell.action(messages.text("cart.clear"), false, () -> confirmClear(overlay));
        clear.setId("clear-cart");
        actions.getChildren().add(clear);

        var left = scroller(lines);
        waitTime = FoodCard.label("", "cart-wait");
        waitTime.setId("cart-wait-estimate");
        allergy = FoodCard.label("", "dish-description");
        var place = CustomerShell.action(messages.text("cart.placeUnavailable"), true, () -> {});
        place.setMaxWidth(Double.MAX_VALUE);
        place.setDisable(true);
        var order = new VBox(11, FoodCard.label(messages.text("cart.orderDetails"), "cart-summary-heading"),
                valueRow(messages.text("cart.service"), messages.text(state.serviceMode()
                        == com.roseroots.customer.models.ServiceMode.DINE_IN ? "service.dineIn" : "service.takeaway"), null),
                FoodCard.label(CustomerShell.sessionContext(state, messages), "catalog-muted"),
                pricing, FoodCard.label(messages.text("cart.waitTitle"), "dish-section-heading"), waitTime,
                FoodCard.label(messages.text("custom.visitAllergies"), "dish-section-heading"), allergy, place,
                FoodCard.label(messages.text("cart.connectionRequired"), "catalog-muted"));
        order.getStyleClass().add("dish-information");
        var right = scroller(order);
        var columns = new HBox(13, left, right);
        columns.setFillHeight(true);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.prefWidthProperty().bind(columns.widthProperty().subtract(13).multiply(.62));
        right.prefWidthProperty().bind(columns.widthProperty().subtract(13).multiply(.38));
        var split = new StackPane(columns);
        split.setMinHeight(0);
        split.widthProperty().addListener((property, previous, next) -> {
            if (next.doubleValue() < 650 && split.getChildren().contains(columns)) {
                left.setContent(null);
                right.setContent(null);
                columns.getChildren().clear();
                var vertical = new VBox(12, lines, order);
                split.getChildren().setAll(scroller(vertical));
            } else if (next.doubleValue() >= 650 && !split.getChildren().contains(columns)) {
                var old = (ScrollPane) split.getChildren().getFirst();
                ((VBox) old.getContent()).getChildren().clear();
                left.setContent(lines);
                right.setContent(order);
                columns.getChildren().setAll(left, right);
                split.getChildren().setAll(columns);
            }
        });
        var body = new VBox(10, new FlowPane(14, 6, title, actions), split);
        body.getStyleClass().add("catalog-content");
        VBox.setVgrow(split, Priority.ALWAYS);
        shell.showContent(body);
        getChildren().addAll(shell, overlay);
        sceneProperty().addListener((property, old, current) -> {
            if (current == null) {
                estimateRevision++;
                expiry.stop();
            } else {
                refreshEstimate();
            }
        });
        refresh();
    }

    private static ScrollPane scroller(Region content) {
        var scroll = new ScrollPane(content);
        scroll.setMinWidth(0);
        scroll.setMinHeight(0);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("catalog-scroll");
        return scroll;
    }

    private void confirmClear(StackPane overlay) {
        Runnable dismiss = () -> {
            overlay.setVisible(false);
            overlay.getChildren().clear();
            clear.requestFocus();
        };
        var cancel = CustomerShell.action(messages.text("catalog.cancelFilters"), false, dismiss);
        cancel.setId("cancel-clear-cart");
        var confirm = CustomerShell.action(messages.text("cart.clear"), true, () -> {
            state.clearDraft(true);
            dismiss.run();
            refresh();
        });
        confirm.setId("confirm-clear-cart");
        var panel = new VBox(12, FoodCard.label(messages.text("cart.clearTitle"), "dish-panel-title"),
                FoodCard.label(messages.text("cart.clearPrompt"), "dish-description"),
                new FlowPane(8, 5, cancel, confirm));
        panel.getStyleClass().add("advanced-filter-panel");
        panel.setMaxWidth(435);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        panel.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                dismiss.run();
                event.consume();
            }
        });
        overlay.getChildren().setAll(panel);
        overlay.setVisible(true);
        cancel.requestFocus();
    }

    private void refresh() {
        var items = state.cart().lines();
        lines.getChildren().setAll(items.stream().map(this::itemRow).toList());
        if (items.isEmpty()) {
            lines.getChildren().add(FoodCard.label(messages.text("custom.emptyCart"), "cart-empty"));
        }

        var amounts = CartPricing.calculate(state.cart().total(), settings.cartCharges(), settings.currency());
        pricing.getChildren().setAll(valueRow(messages.text("cart.subtotal"), money(amounts.subtotal()), null));
        amounts.charges().forEach(charge -> pricing.getChildren().add(valueRow(
                messages.text(charge.rule().labelKey()) + " (" + charge.rule().percent().stripTrailingZeros().toPlainString()
                        + "%)", money(charge.amount()), null)));
        var finalAmount = FoodCard.label(messages.text("cart.total") + "  " + money(amounts.total()), "cart-final-total");
        finalAmount.setId("cart-screen-total");
        finalAmount.setMaxWidth(Double.MAX_VALUE);
        pricing.getChildren().add(finalAmount);
        clear.setDisable(items.isEmpty());
        boolean pending = items.stream().map(this::review).anyMatch(CartScreen::pending);
        allergy.setText(disclosure(state.sessionAllergy(), messages)
                + (pending ? "\n" + messages.text("cart.allergiesPending") : ""));
        shell.refreshCart();
        refreshEstimate();
    }

    private HBox itemRow(CartDraft.Line line) {
        var dish = line.dish();
        var photo = new MenuPhoto(line.item().image(), messages.text("catalog.noPhoto"), 110);
        photo.setMinWidth(104);
        photo.setPrefWidth(125);
        photo.setMaxWidth(145);
        var details = new VBox(5, valueRow(line.item().name().in(state.locale()), money(dish.total()), "cart-line-heading"),
                valueRow(messages.text("cart.baseUnit"), money(line.item().price()), null));
        HBox.setHgrow(details, Priority.ALWAYS);
        for (var group : line.item().customizationGroups()) {
            for (var option : group.choices()) {
                if (dish.selections().getOrDefault(group.id(), java.util.Set.of()).contains(option.id())) {
                    details.getChildren().add(valueRow(group.name().in(state.locale()) + ": " + option.name().in(state.locale()),
                            option.priceAdjustment().signum() == 0 ? money(BigDecimal.ZERO) : "+ " + money(option.priceAdjustment()), null));
                }
            }
        }
        if (dish.allergy().disclosed() || dish.allergy().none()) {
            details.getChildren().add(FoodCard.label(messages.text("custom.itemAllergies") + ": "
                    + disclosure(dish.allergy(), messages), "dish-description"));
        }
        if (!dish.instructions().isBlank()) {
            details.getChildren().add(FoodCard.label(messages.text("cart.preparationNotes") + ": " + dish.instructions(), "dish-description"));
        }
        var review = review(line);
        if (pending(review)) {
            details.getChildren().add(FoodCard.label(reviewWarning(review), "customization-warning"));
        }
        var minus = CustomerShell.action("−", false, () -> {
            state.changeQuantity(line.id(), line.quantity() - 1);
            refresh();
        });
        minus.setAccessibleText(messages.text("custom.less"));
        minus.setDisable(line.quantity() == 1);
        var plus = CustomerShell.action("+", false, () -> {
            state.changeQuantity(line.id(), line.quantity() + 1);
            refresh();
        });
        plus.setId("cart-increase");
        plus.setAccessibleText(messages.text("custom.more"));
        plus.setDisable(line.quantity() == ConfiguredDish.MAX_QUANTITY);
        var editButton = CustomerShell.action(messages.text("custom.edit"), false, () -> edit.accept(line.id()));
        editButton.setId("edit-cart-line");
        var remove = CustomerShell.action(messages.text("custom.remove"), false, () -> {
            state.removeDish(line.id());
            refresh();
        });
        remove.setId("cart-remove");
        details.getChildren().add(new FlowPane(7, 5, editButton, remove, minus,
                FoodCard.label(Integer.toString(line.quantity()), "dish-section-heading"), plus));
        var row = new HBox(10, photo, details);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().addAll("dish-information", "cart-line");
        return row;
    }

    private AllergyReview review(CartDraft.Line line) {
        return AllergyReview.forDish(line.dish(), state.sessionAllergy());
    }

    private static boolean pending(AllergyReview review) {
        return review.status() == AllergyReview.Status.PENDING_ALLERGY_REVIEW;
    }

    private String reviewWarning(AllergyReview review) {
        var direct = riskNames(review.itemRisks().ingredients(), review.visitRisks().ingredients());
        var contact = riskNames(review.itemRisks().crossContact(), review.visitRisks().crossContact());
        return messages.text("cart.allergiesPending")
                + (direct.isBlank() ? "" : "\n" + messages.text("catalog.contains") + ": " + direct)
                + (contact.isBlank() ? "" : "\n" + messages.text("catalog.crossContact") + ": " + contact)
                + (review.itemRisks().uncertain() || review.visitRisks().uncertain()
                        ? "\n" + messages.text("custom.uncertain") : "");
    }

    private String riskNames(java.util.Set<MenuItem.Allergen> first, java.util.Set<MenuItem.Allergen> second) {
        return Stream.concat(first.stream(), second.stream()).distinct().sorted()
                .map(value -> MenuText.label(messages, value)).collect(Collectors.joining(", "));
    }

    private static HBox valueRow(String label, String value, String style) {
        var start = FoodCard.label(label, style == null ? "dish-description" : style);
        var end = FoodCard.label(value, style == null ? "cart-amount" : style);
        end.setMinWidth(Region.USE_PREF_SIZE);
        end.setWrapText(false);
        start.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(start, Priority.ALWAYS);
        var row = new HBox(7, start, end);
        row.setAlignment(Pos.BASELINE_LEFT);
        return row;
    }

    private String money(BigDecimal amount) {
        return MenuText.price(amount, settings.currency(), state.locale());
    }

    private void refreshEstimate() {
        long requested = ++estimateRevision;
        expiry.stop();
        waitTime.setText(messages.text("cart.waitUnavailable"));
        var session = state.terminalSession();
        if (getScene() == null || session == null || !session.canSubmitOrders() || state.cart().lines().isEmpty()) return;
        var items = state.cart().lines().stream().map(line -> new WaitEstimate.Item(line.item().id(),
                line.quantity(), line.dish().selections(), pending(review(line)))).toList();
        estimates.estimate(new WaitEstimate.Request(session.sessionId(), items)).whenComplete((result, error) ->
                Platform.runLater(() -> {
                    if (requested != estimateRevision || getScene() == null || error != null) return;
                    var now = Instant.now();
                    WaitEstimateService.current(result, now).ifPresent(value -> {
                        waitTime.setText(value.earliest().toMinutes() + "–" + value.latest().toMinutes()
                                + " " + messages.text("catalog.minutes") + " · " + messages.text("cart.waitNotGuaranteed"));
                        expiry.setDuration(javafx.util.Duration.millis(Math.max(1,
                                java.time.Duration.between(now, value.validUntil()).toMillis())));
                        expiry.setOnFinished(event -> refreshEstimate());
                        expiry.playFromStart();
                    });
                }));
    }

    private static String disclosure(AllergyDeclaration declaration, Messages messages) {
        if (declaration.none()) return messages.text("custom.none");
        if (!declaration.disclosed()) return messages.text("custom.unreviewed");
        return Stream.concat(declaration.allergens().stream().sorted()
                .map(value -> DishCustomizer.allergenLabel(messages, value)), Stream.of(declaration.other())
                .filter(value -> !value.isBlank())).collect(Collectors.joining(", "));
    }
}
