package com.roseroots.customer.components;

import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.state.ReviewState;
import com.roseroots.customer.utils.Messages;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class ReviewDialog extends Dialog<Void> {
    public ReviewDialog(Window owner, CustomerState customer) {
        initOwner(owner);
        var visit = customer.reviews().visit(customer.terminalSession());
        var text = new Messages(customer.locale());
        setTitle(text.text("review.title"));
        var pane = getDialogPane();
        pane.getStylesheets().add(getClass().getResource("/styles/theme.css").toExternalForm());
        pane.getStyleClass().add("assistance-dialog");
        text.applyDirection(pane);
        if ("am".equals(text.language())) pane.getStyleClass().add("ethiopic");
        pane.setPrefWidth(Math.min(680, owner.getWidth() - 18));
        pane.getButtonTypes().add(ButtonType.CLOSE);
        var close = (Button) pane.lookupButton(ButtonType.CLOSE);
        close.setText(text.text("assistance.close"));
        close.getStyleClass().addAll("catalog-action", "action-secondary");

        var heading = FoodCard.label(text.text("review.title"), "dish-panel-title");
        var rating = FoodCard.label("", "dish-description");
        var stars = new FlowPane(6, 4);
        stars.setAlignment(Pos.CENTER_LEFT);
        var buttons = new ArrayList<Button>();
        for (int i = 1; i <= 5; i++) {
            int value = i;
            var star = new Button("☆");
            star.setId("review-star-" + value);
            var number = java.text.NumberFormat.getIntegerInstance(customer.locale());
            star.setAccessibleText(text.text("review.rating") + " " + number.format(value) + " / " + number.format(5));
            star.getStyleClass().add("review-star");
            star.setOnAction(event -> {
                var current = visit.draft();
                visit.edit(new ReviewState.Draft(current.stars() != null && current.stars() == value ? null : value,
                        current.topics(), current.comment()));
            });
            stars.getChildren().add(star);
            buttons.add(star);
        }
        var topicChoices = new EnumMap<ReviewState.Topic, CheckBox>(ReviewState.Topic.class);
        var topics = new FlowPane(13, 7);
        for (var topic : ReviewState.Topic.values()) {
            var choice = new CheckBox(text.text("review.topic." + topic.name().toLowerCase(Locale.ROOT)));
            choice.setSelected(visit.draft().topics().contains(topic));
            topicChoices.put(topic, choice);
            topics.getChildren().add(choice);
        }
        var comment = new TextArea(visit.draft().comment());
        comment.setId("review-comment");
        comment.setPromptText(text.text("review.comment"));
        comment.setAccessibleText(text.text("review.comment"));
        comment.setPrefRowCount(3);
        comment.setWrapText(true);
        Runnable edit = () -> {
            var checked = EnumSet.noneOf(ReviewState.Topic.class);
            topicChoices.forEach((topic, box) -> {
                if (box.isSelected()) checked.add(topic);
            });
            visit.edit(new ReviewState.Draft(visit.draft().stars(), checked, comment.getText()));
        };
        topicChoices.values().forEach(box -> box.selectedProperty().addListener((p, old, selected) -> edit.run()));
        comment.textProperty().addListener((p, old, value) -> edit.run());
        var submit = CustomerShell.action(text.text("review.submit"), true, visit::submit);
        submit.setId("review-submit");
        var retry = CustomerShell.action(text.text("review.retry"), true, visit::retry);
        var refresh = CustomerShell.action(text.text("review.refresh"), false, visit::refresh);
        var privacy = FoodCard.label(text.text("review.kitchenPrivacy"), "catalog-muted");
        var status = FoodCard.label("", "dish-description");
        status.setId("review-status");
        var editor = new VBox(10, FoodCard.label(text.text("review.rating"), "dish-section-heading"), stars, rating,
                FoodCard.label(text.text("review.topics"), "dish-section-heading"), topics,
                FoodCard.label(text.text("review.comment"), "dish-section-heading"), comment, privacy);
        var actions = new FlowPane(8, 5, submit, retry, refresh);
        var body = new VBox(11, heading, FoodCard.label(text.text("review.optional"), "catalog-muted"),
                editor, status, actions);
        body.getStyleClass().addAll("dish-information", "assistance-body");
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(470, Math.max(220, owner.getHeight() - 125)));
        scroll.getStyleClass().add("catalog-scroll");
        pane.setContent(scroll);

        Runnable render = () -> {
            boolean accepted = visit.phase() == ReviewState.Phase.ACCEPTED;
            editor.setVisible(!accepted);
            editor.setManaged(!accepted);
            actions.setVisible(!accepted);
            actions.setManaged(!accepted);
            var selected = visit.draft().stars();
            for (int i = 0; i < buttons.size(); i++) {
                buttons.get(i).setText(selected != null && i < selected ? "★" : "☆");
                buttons.get(i).setDisable(!visit.writable());
            }
            rating.setText(selected == null ? text.text("review.unrated") : selected + " / 5");
            topicChoices.values().forEach(box -> box.setDisable(!visit.writable()));
            comment.setEditable(visit.writable());
            submit.setDisable(!visit.canSend());
            retry.setVisible(visit.phase() == ReviewState.Phase.UNCERTAIN);
            retry.setManaged(retry.isVisible());
            retry.setDisable(!visit.available() || visit.checking());
            refresh.setDisable(!visit.available() || visit.checking() || visit.phase() == ReviewState.Phase.SENDING);
            var sharesWithKitchen = visit.draft().topics().stream().anyMatch(topic -> switch (topic) {
                case FOOD_QUALITY, TASTE, TEMPERATURE, ORDER_ACCURACY, PREPARATION_SPEED -> true;
                default -> false;
            });
            privacy.setVisible(sharesWithKitchen);
            privacy.setManaged(sharesWithKitchen);
            String key = switch (visit.phase()) {
                case ACCEPTED -> "review.accepted";
                case SENDING -> "review.sending";
                case UNCERTAIN -> "review.uncertain";
                case REJECTED -> "review.rejected";
                case DRAFT -> !visit.validLength() ? "review.tooLong"
                        : visit.checking() ? "review.checking"
                        : !visit.available() || !visit.checked() ? "review.unavailable"
                        : visit.eligible() ? "review.ready" : "review.notEligible";
            };
            status.setText(text.text(key));
        };
        setOnShown(event -> {
            visit.observe(render);
            visit.refresh();
            render.run();
        });
        setOnHidden(event -> visit.observe(() -> {}));
        render.run();
    }
}
