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
import com.roseroots.customer.state.OrderConfirmation;
import com.roseroots.customer.models.Ordering;
import javafx.scene.control.CheckBox;
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
    private final OrderConfirmation confirmation;
    private final Runnable goAccepted;
    private final VBox reviewControls = new VBox(7);
    private final CheckBox reviewed;
    private final PauseTransition quoteExpiry = new PauseTransition();
    private boolean editingVisit;
    private final PauseTransition expiry = new PauseTransition();
    private long estimateRevision;

    public CartScreen(RestaurantSettings settings, CustomerState state, WaitEstimateService estimates,
                      Consumer<Locale> language, Runnable home, Runnable menu, Consumer<UUID> edit,
                      Consumer<String> category, Runnable destination, OrderConfirmation confirmation, Runnable goOrders, Runnable goAccepted) {
        this.settings = settings;
        this.state = state;
        this.estimates = estimates;
        this.edit = edit;
        this.confirmation = confirmation;
        this.goAccepted = goAccepted;
        messages = new Messages(state.locale());
        var overlay = new StackPane();
        overlay.setVisible(false);
        overlay.getStyleClass().add("filter-overlay");
        shell = new CustomerShell(settings, state, messages, language, home, null);
        shell.showOrdersAccess(goOrders, false);
        overlay.visibleProperty().addListener((property, before, visible) -> shell.setDisable(visible));
        shell.displayCategories(state.menu().catalog(), state.locale(), category);

        var title = FoodCard.label(messages.text(confirmation == null ? "cart.title" : "confirm.title"), "catalog-heading");
        if (confirmation != null) title.getStyleClass().add("confirmation-heading");
        var back = CustomerShell.action(messages.text(confirmation == null ? "cart.continue" : "confirm.back"),
                false, confirmation == null ? menu : destination);
        back.setId(confirmation == null ? "cart-continue" : "confirmation-back");
        clear = CustomerShell.action(messages.text("cart.clear"), false, () -> confirmClear(overlay));
        clear.setId("clear-cart");

        var left = scroller(lines);
        waitTime = FoodCard.label("", "cart-wait");
        waitTime.setId("cart-wait-estimate");
        allergy = FoodCard.label("", "dish-description");

        reviewed = new CheckBox(messages.text("confirm.reviewed"));
        reviewed.setWrapText(true);
        reviewed.setId("confirm-allergies-reviewed");
        if (confirmation == null) {
            var review = CustomerShell.action(messages.text("confirm.open"), true, destination);
            review.setMaxWidth(Double.MAX_VALUE);
            review.setId("review-order");
            reviewControls.getChildren().add(review);
        }
        var order = new VBox(11, FoodCard.label(messages.text("cart.orderDetails"), "cart-summary-heading"),
                valueRow(messages.text("cart.service"), messages.text(state.serviceMode()
                        == com.roseroots.customer.models.ServiceMode.DINE_IN ? "service.dineIn" : "service.takeaway"), null),
                FoodCard.label(CustomerShell.sessionContext(state, messages), "catalog-muted"),
                pricing, FoodCard.label(messages.text("cart.waitTitle"), "dish-section-heading"), waitTime,
                FoodCard.label(messages.text("custom.visitAllergies"), "dish-section-heading"), allergy, reviewControls);
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
        Region heading;
        if (confirmation == null) {
            heading = new FlowPane(14, 6, title, new FlowPane(7, 5, back, clear));
        } else {
            back.setMinWidth(Region.USE_PREF_SIZE);
            var reviewHeading = new HBox(14, title, back);
            reviewHeading.setAlignment(Pos.CENTER_LEFT);
            heading = reviewHeading;
        }
        var body = new VBox(10, heading, split);
        body.getStyleClass().add("catalog-content");
        VBox.setVgrow(split, Priority.ALWAYS);
        shell.showContent(body, confirmation == null);
        getChildren().addAll(shell, overlay);
        sceneProperty().addListener((property, old, current) -> {
            if (current == null) {
                estimateRevision++;
                expiry.stop();
                quoteExpiry.stop();
            } else {
                if (confirmation != null) Platform.runLater(this::initialQuote);
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
        if (confirmation == null) {
            reviewControls.getChildren().getFirst().setDisable(items.isEmpty());
        } else {
            var quote = confirmation.quote();
            if (quote != null) renderServerTotals(quote.totals());
            else pricing.getChildren().add(FoodCard.label(messages.text("confirm.preview"), "catalog-muted"));
            renderConfirmation();
        }
        boolean pending = items.stream().map(this::review).anyMatch(CartScreen::pending);
        allergy.setText(MenuText.allergyDisclosure(state.sessionAllergy(), messages)
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
        var quote = confirmation == null ? null : confirmation.quote();
        var price = quote == null ? null : quote.lines().stream()
                .filter(value -> value.clientLineId().equals(line.id())).findFirst().orElse(null);
        var details = new VBox(5, valueRow(line.item().name().in(state.locale()),
                price == null ? money(dish.total()) : minor(price.extendedMinor()), "cart-line-heading"),
                valueRow(messages.text("cart.baseUnit"),
                        price == null ? money(line.item().price()) : minor(price.baseUnitMinor()), null));
        if (confirmation != null) {
            details.getChildren().add(valueRow(messages.text("confirm.quantity"), Integer.toString(line.quantity()), null));
        }
        HBox.setHgrow(details, Priority.ALWAYS);
        for (var group : line.item().customizationGroups()) {
            for (var option : group.choices()) {
                if (dish.selections().getOrDefault(group.id(), java.util.Set.of()).contains(option.id())) {
                    var adjustment = price == null ? option.priceAdjustment() : price.options().stream()
                            .filter(value -> value.groupId().toString().equals(group.id())
                                    && value.optionId().toString().equals(option.id()))
                            .map(value -> BigDecimal.valueOf(value.unitAdjustmentMinor(), 2)).findFirst().orElseThrow();
                    details.getChildren().add(valueRow(group.name().in(state.locale()) + ": " + option.name().in(state.locale()),
                            (adjustment.signum() > 0 ? "+ " : "") + money(adjustment), null));
                }
            }
        }
        if (dish.allergy().disclosed() || dish.allergy().none()) {
            details.getChildren().add(FoodCard.label(messages.text("custom.itemAllergies") + ": "
                    + MenuText.allergyDisclosure(dish.allergy(), messages), "dish-description"));
        }
        if (!dish.instructions().isBlank()) {
            details.getChildren().add(FoodCard.label(messages.text("cart.preparationNotes") + ": " + dish.instructions(), "dish-description"));
        }
        var review = review(line);
        var safety = reviewWarning(review);
        if (!safety.isBlank()) details.getChildren().add(FoodCard.label(safety, "customization-warning"));
        if (confirmation == null) addEditActions(details, line);
        var row = new HBox(10, photo, details);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().addAll("dish-information", "cart-line");
        return row;
    }

    private void addEditActions(VBox details, CartDraft.Line line) {
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
    }

    private void initialQuote() {
        if (getScene() == null) return;
        if (confirmation.quote() == null && confirmation.canQuote()) refreshQuote();
        else refresh();
    }

    private void refreshQuote() {
        reviewed.setSelected(false);
        if (confirmation.canQuote()) confirmation.requestQuote(this::refresh);
        else refresh();
    }

    private void submit(boolean acknowledged) {
        confirmation.submit(acknowledged, () -> {
            if (getScene() == null) return;
            if (confirmation.phase() == OrderConfirmation.Phase.ACCEPTED && confirmation.accepted() != null) goAccepted.run();
            else refresh();
        });
    }

    private void renderConfirmation() {
        quoteExpiry.stop();
        reviewControls.getChildren().clear();
        shell.setDisable(confirmation.busy());
        if (state.cart().lines().isEmpty() && !confirmation.unresolved()) return;
        if (!confirmation.active()) {
            reviewControls.getChildren().add(FoodCard.label(messages.text("confirm.draft"), "catalog-muted"));
            var place = confirmationAction("cart.placeUnavailable", () -> {});
            place.setDisable(true);
            reviewControls.getChildren().addAll(place, FoodCard.label(messages.text("cart.connectionRequired"), "catalog-muted"));
            return;
        }
        if (confirmation.busy()) {
            var progress = new javafx.scene.control.ProgressIndicator();
            progress.setPrefSize(25, 25);
            reviewControls.getChildren().add(new HBox(9, progress,
                    FoodCard.label(messages.text(confirmation.phase() == OrderConfirmation.Phase.SENDING
                            ? "confirm.sending" : "confirm.quoting"), "dish-description")));
            return;
        }
        if (confirmation.unresolved() || !confirmation.problem().isEmpty()) {
            String message = confirmation.unresolved() ? "confirm.uncertain" : switch (confirmation.problem()) {
                case "QUOTE_EXPIRED", "PRICE_CHANGED" -> "confirm.requote";
                case "INVALID_SELECTION", "ITEM_UNAVAILABLE" -> "confirm.invalid";
                case "IDEMPOTENCY_CONFLICT" -> "confirm.conflict";
                case "SESSION_CLOSED", "SESSION_EXPIRED", "UNAUTHENTICATED" -> "confirm.session";
                case "REVIEW_REQUIRED" -> "confirm.reviewRequired";
                default -> "confirm.failure";
            };
            reviewControls.getChildren().add(FoodCard.label(messages.text(message), "customization-warning"));
            confirmation.diagnostics().forEach(detail ->
                    reviewControls.getChildren().add(FoodCard.label(detail, "dish-description")));
        }
        if (confirmation.unresolved()) {
            var retry = confirmationAction("confirm.retry", () -> submit(false));
            retry.setDisable(!confirmation.retryPossible());
            reviewControls.getChildren().add(retry);
            return;
        }
        if (editingVisit || (!state.sessionAllergy().none() && !state.sessionAllergy().disclosed())) {
            var prior = state.sessionAllergy();
            var none = CustomerShell.action(messages.text("custom.none"), false, () -> {
                state.rememberAllergy(new AllergyDeclaration(true, java.util.Set.of(), ""));
                editingVisit = false;
                refreshQuote();
            });
            none.setWrapText(true);
            var other = new javafx.scene.control.TextArea(prior.other());
            other.setPromptText(messages.text("custom.other"));
            other.setPrefRowCount(2);
            other.setWrapText(true);
            var choices = new FlowPane(9, 7);
            var allergens = new java.util.EnumMap<AllergyDeclaration.Allergen, CheckBox>(AllergyDeclaration.Allergen.class);
            for (var allergen : AllergyDeclaration.Allergen.values()) {
                var check = new CheckBox(DishCustomizer.allergenLabel(messages, allergen));
                check.setSelected(prior.allergens().contains(allergen));
                choices.getChildren().add(check);
                allergens.put(allergen, check);
            }
            var save = CustomerShell.action(messages.text("confirm.saveAllergies"), false, () -> {
                var selected = java.util.EnumSet.noneOf(AllergyDeclaration.Allergen.class);
                allergens.forEach((allergen, check) -> {
                    if (check.isSelected()) selected.add(allergen);
                });
                state.rememberAllergy(new AllergyDeclaration(false, selected, other.getText()));
                editingVisit = false;
                refreshQuote();
            });
            Runnable validate = () -> {
                boolean selected = allergens.values().stream().anyMatch(CheckBox::isSelected);
                save.setDisable(other.getText().length() > 240 || (!selected && other.getText().isBlank()));
                none.setDisable(selected || !other.getText().isBlank());
            };
            other.textProperty().addListener((property, previous, next) -> validate.run());
            allergens.values().forEach(check -> check.selectedProperty().addListener((property, previous, next) -> validate.run()));
            validate.run();
            var cancel = CustomerShell.action(messages.text("catalog.cancelFilters"), false, () -> {
                editingVisit = false;
                refresh();
            });
            var buttons = new FlowPane(7, 6, none, save);
            if (editingVisit) buttons.getChildren().add(cancel);
            reviewControls.getChildren().addAll(choices, other, buttons);
            return;
        }
        var editVisit = CustomerShell.action(messages.text("confirm.editAllergies"), false, () -> {
            editingVisit = true;
            refresh();
        });
        reviewControls.getChildren().add(editVisit);
        var quote = confirmation.quote();
        if (quote == null) {
            var getQuote = confirmationAction("confirm.quote", this::refreshQuote);
            getQuote.setDisable(!confirmation.canQuote());
            reviewControls.getChildren().add(getQuote);
            return;
        }
        reviewControls.getChildren().add(FoodCard.label(messages.text("confirm.quoted"), "catalog-muted"));
        var place = confirmationAction("confirm.place", () -> submit(reviewed.isSelected()));
        place.disableProperty().bind(reviewed.selectedProperty().not());
        reviewControls.getChildren().addAll(reviewed, place);
        quoteExpiry.setDuration(javafx.util.Duration.millis(Math.max(1,
                java.time.Duration.between(Instant.now(), quote.expiresAt()).toMillis())));
        quoteExpiry.setOnFinished(event -> {
            refreshQuote();
        });
        quoteExpiry.playFromStart();
    }

    private Button confirmationAction(String key, Runnable action) {
        var button = CustomerShell.action(messages.text(key), true, action);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setId("place-order");
        return button;
    }

    private void renderServerTotals(Ordering.Totals totals) {
        pricing.getChildren().setAll(valueRow(messages.text("cart.subtotal"), minor(totals.subtotalMinor()), null));
        if (totals.serviceMinor() != 0) pricing.getChildren().add(valueRow(messages.text("confirm.serviceCharge"), minor(totals.serviceMinor()), null));
        if (totals.taxMinor() != 0) pricing.getChildren().add(valueRow(messages.text("confirm.tax"), minor(totals.taxMinor()), null));
        var amount = FoodCard.label(messages.text("cart.total") + "  " + minor(totals.totalMinor()), "cart-final-total");
        amount.setMaxWidth(Double.MAX_VALUE);
        pricing.getChildren().add(amount);
    }

    private String minor(long amount) {
        return MenuText.price(BigDecimal.valueOf(amount, 2), java.util.Currency.getInstance("KES"), state.locale());
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
        return Stream.of(
                direct.isBlank() ? "" : messages.text("confirm.directConflict") + ": " + direct,
                contact.isBlank() ? "" : messages.text("confirm.possibleContact") + ": " + contact,
                review.itemRisks().uncertain() || review.visitRisks().uncertain() ? messages.text("custom.uncertain") : "")
                .filter(text -> !text.isBlank()).collect(Collectors.joining("\n"));
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


}
