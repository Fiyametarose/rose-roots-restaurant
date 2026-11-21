package com.roseroots.customer.components;

import com.roseroots.customer.services.AssistanceService.Context;
import com.roseroots.customer.services.AssistanceService.Type;
import com.roseroots.customer.state.AssistanceState;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.Messages;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class AssistanceDialog extends Dialog<Void> {
    private final Messages messages;
    private final AssistanceState.Visit visit;
    private final TextArea message = new TextArea();
    private final VBox requestList = new VBox(8);
    private final VBox form = new VBox(12);
    private final VBox statusPane;
    private final javafx.scene.control.Button viewStatus;
    private final Label connection;
    private final Label result;
    private final javafx.scene.control.Button send;
    private final javafx.scene.control.Button retry;
    private final ToggleGroup types = new ToggleGroup();
    private final DateTimeFormatter time;

    public AssistanceDialog(Window owner, CustomerState state, boolean fixture) {
        initOwner(owner);
        messages = new Messages(state.locale());
        time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(state.locale())
                .withZone(ZoneId.systemDefault());
        visit = state.assistance().visit(Context.from(state.terminalSession()));
        setTitle(messages.text("catalog.assistance"));
        var pane = getDialogPane();
        pane.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles/theme.css")).toExternalForm());
        pane.getStyleClass().add("assistance-dialog");
        pane.setPrefWidth(Math.min(760, owner.getWidth() - 18));
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.lookupButton(ButtonType.CLOSE).setId("assistance-close");
        var close = (javafx.scene.control.Button) pane.lookupButton(ButtonType.CLOSE);
        close.setText(messages.text("assistance.close"));
        close.getStyleClass().addAll("catalog-action", "action-secondary");

        var title = FoodCard.label(messages.text("assistance.title"), "dish-panel-title");
        var context = FoodCard.label(CustomerShell.sessionContext(state, messages), "catalog-muted");
        connection = FoodCard.label("", "catalog-muted");
        connection.setId("assistance-connection");
        result = FoodCard.label("", "assistance-result");
        result.setId("assistance-result");

        var grid = new TilePane(9, 8);
        grid.setPrefColumns(3);
        grid.setTileAlignment(Pos.CENTER);
        grid.setAlignment(Pos.CENTER);
        for (var type : Type.values()) {
            var choice = new ToggleButton(typeLabel(type));
            choice.setId("assistance-" + type.name().toLowerCase(Locale.ROOT));
            choice.setToggleGroup(types);
            choice.setUserData(type);
            choice.setWrapText(true);
            choice.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            choice.setGraphic(new InterfaceIcon(switch (type) {
                case WAITER -> InterfaceIcon.Kind.MAIN;
                case DRINKING_WATER -> InterfaceIcon.Kind.DRINK;
                case CUTLERY -> InterfaceIcon.Kind.CUTLERY;
                case NAPKINS -> InterfaceIcon.Kind.NAPKINS;
                case ORDER_HELP -> InterfaceIcon.Kind.HELP;
                case BILL -> InterfaceIcon.Kind.ORDERS;
                case OTHER -> InterfaceIcon.Kind.EDIT;
            }));
            choice.getStyleClass().add("assistance-choice");
            grid.getChildren().add(choice);
        }
        message.setPromptText(messages.text("assistance.message"));
        message.setAccessibleText(messages.text("assistance.message"));
        message.setId("assistance-message");
        message.setWrapText(true);
        message.setPrefRowCount(2);
        send = CustomerShell.action(messages.text("assistance.send"), true,
                () -> visit.send(selected(), selected() == Type.OTHER ? message.getText() : ""));
        send.setId("assistance-send");
        retry = CustomerShell.action(messages.text("assistance.retry"), true, visit::retry);
        retry.setId("assistance-retry");

        statusPane = new VBox(8, FoodCard.label(messages.text("assistance.statusTitle"), "dish-section-heading"), requestList);
        statusPane.setId("assistance-status-view");
        viewStatus = CustomerShell.action(messages.text("assistance.viewStatus"), false, () -> viewStatus(true));
        viewStatus.setId("assistance-view-status");
        var back = CustomerShell.action(messages.text("assistance.new"), false, () -> viewStatus(false));
        back.setId("assistance-new");
        statusPane.getChildren().add(back);
        var actions = new FlowPane(8, 6, send, retry, viewStatus);
        actions.setAlignment(Pos.CENTER);
        form.getChildren().addAll(grid, message, actions);
        var body = new VBox(12, title, context, connection, result, form, statusPane);
        body.getStyleClass().add("assistance-body");
        body.setAlignment(Pos.TOP_CENTER);
        if (fixture) body.getChildren().addFirst(FoodCard.label(messages.text("assistance.fixture"), "customization-warning"));
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(550, Math.max(230, owner.getHeight() - 120)));
        scroll.getStyleClass().add("catalog-scroll");
        pane.setContent(scroll);
        types.selectedToggleProperty().addListener((property, old, value) -> render());
        message.textProperty().addListener((property, old, value) -> render());
        setOnShown(event -> {
            observation = visit.observe(this::render);
            grid.getChildren().getFirst().requestFocus();
        });
        setOnHidden(event -> {
            if (observation != null) observation.close();
            observation = null;
        });
        render();
        viewStatus(false);
    }

    private com.roseroots.customer.services.AssistanceService.Watch observation;

    private void viewStatus(boolean show) {
        visible(form, !show);
        visible(viewStatus, !show);
        visible(statusPane, show);
    }

    private void render() {
        var type = selected();
        visible(message, type == Type.OTHER);
        send.setDisable(!visit.canSend(type) || (type == Type.OTHER && message.getText().strip().length() > 240));
        var outcome = visit.outcome();
        visible(retry, outcome == AssistanceState.Outcome.UNCERTAIN);
        retry.setDisable(!visit.canRetry());
        connection.setText(messages.text(key("connection", visit.connection())));
        String feedback = type == Type.OTHER && message.getText().strip().length() > 240 ? "assistance.tooLong"
                : outcome != AssistanceState.Outcome.IDLE ? key("outcome", outcome)
                : type != null && visit.duplicate(type) ? "assistance.duplicate" : "";
        result.setText(feedback.isEmpty() ? "" : messages.text(feedback));
        visible(result, !feedback.isEmpty());
        requestList.getChildren().clear();
        for (var item : visit.requests()) {
            var card = new VBox(4, FoodCard.label(typeLabel(item.type()), "dish-section-heading"),
                    FoodCard.label(messages.text(key("status", item.status())), "assistance-result"),
                    FoodCard.label(item.id() + "  ·  " + time.format(item.submittedAt()), "catalog-muted"));
            if (!item.message().isBlank()) card.getChildren().add(FoodCard.label(item.message(), "dish-description"));
            card.getStyleClass().add("dish-information");
            requestList.getChildren().add(card);
        }
        if (requestList.getChildren().isEmpty()) {
            requestList.getChildren().add(FoodCard.label(messages.text("assistance.empty"), "catalog-muted"));
        }
    }

    private Type selected() {
        return types.getSelectedToggle() == null ? null : (Type) types.getSelectedToggle().getUserData();
    }

    private String typeLabel(Type type) { return messages.text(key("type", type)); }

    private static String key(String group, Enum<?> value) {
        return "assistance." + group + "." + value.name().toLowerCase(Locale.ROOT);
    }

    private static void visible(javafx.scene.Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }
}
