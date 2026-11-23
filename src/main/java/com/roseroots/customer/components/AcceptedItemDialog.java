package com.roseroots.customer.components;

import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.services.MenuService;
import com.roseroots.customer.services.OrderService.Problem;
import com.roseroots.customer.services.api.RestAcceptedItemChanges;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

public final class AcceptedItemDialog extends Dialog<Void> {
    private final VBox form = new VBox(8);
    private final VBox options = new VBox(6);
    private final Label status = new Label();
    private final Button submit = new Button();
    private final Messages messages;
    private final Map<String, Map<String, CheckBox>> selections = new LinkedHashMap<>();
    private final ComboBox<MenuItem> dish = new ComboBox<>();
    private final Spinner<Integer> quantity;
    private final TextArea instructions = new TextArea();
    private final Runnable refresh;
    private Ordering.Line proposal;
    private RestAcceptedItemChanges.Preview preview;
    private UUID key = UUID.randomUUID();
    private boolean busy;

    public AcceptedItemDialog(Window owner, Locale locale, RestAcceptedItemChanges api, MenuService menu,
                              Ordering.Accepted order, Ordering.AcceptedLine item, boolean cancel, Runnable refresh) {
        this.refresh = refresh;
        messages = new Messages(locale);
        initOwner(owner);
        setTitle(messages.text(cancel ? "orders.itemCancel" : "orders.itemEdit"));
        var pane = getDialogPane();
        pane.getStylesheets().addAll(owner.getScene().getStylesheets());
        pane.getStyleClass().add("assistance-dialog");
        if ("am".equals(messages.language())) pane.getStyleClass().add("ethiopic");
        messages.applyDirection(pane);
        pane.getButtonTypes().add(new ButtonType(messages.text("pay.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        pane.lookupButton(pane.getButtonTypes().getFirst()).disableProperty().bind(form.disableProperty());
        form.setPadding(new Insets(12));
        status.setWrapText(true);
        var content = new VBox(8, new Label(item.pricing().name()), form, status);
        content.setPadding(new Insets(10));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(470);
        scroll.setPrefViewportHeight(cancel ? 180 : 430);
        pane.setContent(scroll);
        quantity = new Spinner<>(1, 99, item.pricing().quantity());
        quantity.setId("accepted-item-quantity");
        submit.setId(cancel ? "confirm-item-cancel" : "preview-item-edit");
        if (cancel) {
            submit.setText(messages.text("orders.itemConfirmCancel"));
            form.getChildren().setAll(new Label(messages.text("orders.itemCancelQuestion")), submit);
            submit.setOnAction(event -> run(() -> api.cancel(order,item,key), ignored -> successful()));
            return;
        }
        dish.setId("accepted-item-dish");
        dish.setMaxWidth(Double.MAX_VALUE);
        dish.setConverter(new StringConverter<>() {
            @Override public String toString(MenuItem value) { return value == null ? "" : value.name().in(locale); }
            @Override public MenuItem fromString(String value) { throw new UnsupportedOperationException(); }
        });
        instructions.setText(item.instructions());
        instructions.setPrefRowCount(3);
        instructions.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().codePointCount(0, change.getControlNewText().length()) <= 240 ? change : null));
        form.getChildren().setAll(field("orders.itemDish", dish), field("orders.itemQuantity", quantity),
                options, field("orders.itemInstructions", instructions), submit);
        submit.setText(messages.text("orders.itemPreview"));
        submit.setDisable(true);
        dish.valueProperty().addListener((observable, previous, next) -> {
            showChoices(next,item,locale);
            invalidate();
        });
        quantity.valueProperty().addListener((property, before, after) -> invalidate());
        instructions.textProperty().addListener((property, before, after) -> invalidate());
        submit.setOnAction(event -> {
            if (preview != null) {
                run(() -> api.save(order,item,proposal,preview,key), ignored -> successful());
            } else if (dish.getValue() != null) {
                var chosen = new LinkedHashMap<String, Set<String>>();
                selections.forEach((group, choices) -> {
                    var selected = new LinkedHashSet<String>();
                    choices.forEach((id, box) -> { if (box.isSelected()) selected.add(id); });
                    chosen.put(group, selected);
                });
                proposal = new Ordering.Line(item.pricing().clientLineId(),dish.getValue().id(),quantity.getValue(),
                        chosen,item.allergy(),instructions.getText());
                run(() -> api.preview(order,item,proposal), value -> {
                    preview = value;
                    key = UUID.randomUUID();
                    status.setText(messages.text("orders.itemPrice") + " " + MenuText.price(
                            BigDecimal.valueOf(value.pricing().extendedMinor(),2), Currency.getInstance("KES"), locale));
                    submit.setText(messages.text("orders.itemConfirmEdit"));
                    submit.setId("confirm-item-edit");
                });
            }
        });
        setOnShown(event -> run(menu::loadCatalog, catalog -> {
            var available = catalog.items().stream().filter(i -> i.availability() == MenuItem.Availability.AVAILABLE).toList();
            dish.getItems().setAll(available);
            dish.setValue(available.stream().filter(i -> i.id().equals(item.pricing().menuItemId().toString()))
                    .findFirst().orElse(null));
            submit.setDisable(dish.getValue() == null);
            if (available.isEmpty()) status.setText(messages.text("orders.itemUnavailable"));
        }));
    }

    private VBox field(String key, Control control) {
        var label = new Label(messages.text(key));
        label.setLabelFor(control);
        return new VBox(3,label,control);
    }

    private void showChoices(MenuItem selected, Ordering.AcceptedLine original, Locale locale) {
        options.getChildren().clear();
        selections.clear();
        if (selected == null) return;
        boolean sameDish = selected.id().equals(original.pricing().menuItemId().toString());
        for (var group : selected.customizationGroups()) {
            options.getChildren().add(new Label(group.name().in(locale) + " (" + group.minimum() + "?" + group.maximum() + ")"));
            var boxes = new LinkedHashMap<String, CheckBox>();
            selections.put(group.id(),boxes);
            for (var choice : group.choices()) {
                var box = new CheckBox(choice.name().in(locale));
                box.setWrapText(true);
                box.setSelected(sameDish && original.pricing().options().stream().anyMatch(
                        option -> option.groupId().toString().equals(group.id()) && option.optionId().toString().equals(choice.id())));
                box.selectedProperty().addListener((property, previous, current) -> invalidate());
                boxes.put(choice.id(),box);
                options.getChildren().add(box);
            }
        }
    }

    private void invalidate() {
        preview = null;
        submit.setText(messages.text("orders.itemPreview"));
        submit.setId("preview-item-edit");
        submit.setDisable(dish.getValue() == null);
        status.setText("");
    }

    private <T> void run(Supplier<CompletionStage<T>> action, Consumer<T> success) {
        if (busy) return;
        busy = true;
        form.setDisable(true);
        try {
            action.get().whenComplete((result, failure) -> Platform.runLater(() -> complete(result,failure,success)));
        } catch (RuntimeException failure) {
            complete(null,failure,success);
        }
    }

    private <T> void complete(T result, Throwable failure, Consumer<T> success) {
        if (!isShowing()) return;
        busy = false;
        form.setDisable(false);
        if (failure == null) { success.accept(result); return; }
        Throwable cause = com.roseroots.customer.state.PaymentCheckout.root(failure);
        String code = cause instanceof Problem problem ? problem.code() : "";
        String label = switch (code) {
            case "ITEM_PREPARATION_STARTED" -> "orders.itemStarted";
            case "ALLERGEN_CONFLICT" -> "orders.itemAllergen";
            case "VERSION_CONFLICT", "PAYMENT_PENDING", "ALREADY_SETTLED", "SESSION_NOT_OPEN" -> "orders.itemStale";
            case "PRICE_CHANGED" -> "orders.itemReprice";
            case "CONNECTION_INTERRUPTED", "SERVICE_UNAVAILABLE" -> "orders.itemRetry";
            default -> "orders.itemUnavailable";
        };
        if ("PRICE_CHANGED".equals(code)) invalidate();
        if (Set.of("ITEM_PREPARATION_STARTED","VERSION_CONFLICT","PAYMENT_PENDING","ALREADY_SETTLED","SESSION_NOT_OPEN").contains(code)) {
            form.setDisable(true);
            getDialogPane().lookupButton(getDialogPane().getButtonTypes().getFirst()).disableProperty().unbind();
            refresh.run();
        }
        status.setText(messages.text(label));
    }

    private void successful() {
        refresh.run();
        close();
    }
}
