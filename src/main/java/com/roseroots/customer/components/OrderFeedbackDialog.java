package com.roseroots.customer.components;

import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.services.OrderService;
import com.roseroots.customer.services.api.RestOrderFeedback;
import com.roseroots.customer.state.ReviewState.Topic;
import com.roseroots.customer.utils.Messages;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class OrderFeedbackDialog extends Dialog<Void> {
    public OrderFeedbackDialog(Window owner, Locale locale, RestOrderFeedback service, Ordering.Accepted order,
                               Ordering.AcceptedLine line, String reference, String image, Runnable refresh) {
        initOwner(owner);
        var messages = new Messages(locale);
        boolean dish = line != null;
        setTitle(messages.text(dish ? "feedback.dish" : "feedback.order"));
        var pane = getDialogPane();
        messages.applyDirection(pane);
        pane.getStylesheets().add(getClass().getResource("/styles/theme.css").toExternalForm());
        pane.getStyleClass().add("assistance-dialog");
        if ("am".equals(messages.language())) pane.getStyleClass().add("ethiopic");
        pane.setPrefWidth(Math.min(550, Math.max(320, owner.getWidth() - 24)));
        pane.getButtonTypes().add(ButtonType.CLOSE);
        var close = (Button) pane.lookupButton(ButtonType.CLOSE);
        close.setText(messages.text("assistance.close"));
        close.getStyleClass().addAll("catalog-action", "action-secondary");
        var body = new VBox(9, FoodCard.label(reference, "dish-panel-title"));
        body.getStyleClass().addAll("dish-information", "assistance-body");
        var numbers = NumberFormat.getIntegerInstance(locale);
        if (dish) {
            var photo = new MenuPhoto(image, messages.text("catalog.noPhoto"), 115);
            body.getChildren().addAll(photo, FoodCard.label(line.pricing().name() + "  ×"
                    + numbers.format(line.pricing().quantity()), "dish-section-heading"));
        }
        var stars = new FlowPane(6, 5);
        var buttons = new ArrayList<Button>();
        var rating = FoodCard.label(messages.text("feedback.choose"), "dish-description");
        var selected = new javafx.beans.property.SimpleIntegerProperty(0);
        for (int value = 1; value <= 5; value++) {
            int count = value;
            var star = new Button("☆");
            star.setId("food-feedback-star-" + count);
            star.setAccessibleText(messages.text("review.rating") + " " + numbers.format(count) + " / " + numbers.format(5));
            star.getStyleClass().add("review-star");
            star.setOnAction(event -> selected.set(count));
            stars.getChildren().add(star);
            buttons.add(star);
        }
        selected.addListener((p, previous, current) -> {
            for (int i = 0; i < buttons.size(); i++) buttons.get(i).setText(i < current.intValue() ? "★" : "☆");
            rating.setText(numbers.format(current) + " / " + numbers.format(5));
        });
        var editor = new VBox(7, rating, stars);
        var checks = new EnumMap<Topic, CheckBox>(Topic.class);
        if (!dish) {
            var topics = new FlowPane(10, 6);
            RestOrderFeedback.ORDER_TOPICS.stream().sorted().forEach(topic -> {
                var box = new CheckBox(messages.text("review.topic." + topic.name().toLowerCase(Locale.ROOT)));
                checks.put(topic, box);
                topics.getChildren().add(box);
            });
            editor.getChildren().addAll(FoodCard.label(messages.text("review.topics"), "dish-section-heading"), topics);
        }
        var comment = new TextArea();
        comment.setId("food-feedback-comment");
        comment.setPromptText(messages.text("review.comment"));
        comment.setAccessibleText(messages.text("review.comment"));
        comment.setWrapText(true);
        comment.setPrefRowCount(3);
        comment.setTextFormatter(new TextFormatter<>(change -> change.getControlNewText()
                .codePointCount(0, change.getControlNewText().length()) <= 500 ? change : null));
        editor.getChildren().add(comment);
        var status = FoodCard.label("", "dish-description");
        status.setId("food-feedback-status");
        var submit = new Button(messages.text("review.submit"));
        submit.getStyleClass().addAll("catalog-action", "action-primary");
        submit.setId("food-feedback-submit");
        var sending = new javafx.beans.property.SimpleBooleanProperty();
        var complete = new javafx.beans.property.SimpleBooleanProperty();
        submit.disableProperty().bind(sending.or(complete).or(selected.isEqualTo(0)));
        UUID key = UUID.randomUUID();
        submit.setOnAction(event -> {
            sending.set(true);
            editor.setDisable(true);
            status.setText(messages.text("feedback.sending"));
            var topics = EnumSet.noneOf(Topic.class);
            checks.forEach((topic, box) -> { if (box.isSelected()) topics.add(topic); });
            service.send(order.sessionId(), order.id(), dish ? line.id() : null, key, selected.get(), topics, comment.getText())
                    .whenComplete((unused, error) -> Platform.runLater(() -> {
                        sending.set(false);
                        Throwable failure = error;
                        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                        if (failure == null || failure instanceof OrderService.Problem p
                                && "REVIEW_ALREADY_EXISTS".equals(p.code())) {
                            complete.set(true);
                            status.setText(messages.text(failure == null ? "feedback.saved" : "feedback.exists"));
                            refresh.run();
                        } else if (failure instanceof OrderService.Problem p
                                && "REVIEW_NOT_ELIGIBLE".equals(p.code())) {
                            complete.set(true);
                            status.setText(messages.text(dish ? "feedback.dishWait" : "feedback.orderWait"));
                            refresh.run();
                        } else if (failure instanceof OrderService.Problem p && "BAD_REQUEST".equals(p.code())) {
                            editor.setDisable(false);
                            status.setText(messages.text("feedback.invalid"));
                        } else if (failure instanceof OrderService.Problem p
                                && Set.of("UNAUTHENTICATED", "FORBIDDEN", "NOT_FOUND").contains(p.code())) {
                            complete.set(true);
                            status.setText(messages.text("feedback.offline"));
                            refresh.run();
                        } else {
                            status.setText(messages.text("feedback.retry"));
                            submit.setText(messages.text("review.retry"));
                        }
                    }));
        });
        body.getChildren().add(editor);
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(dish ? 405 : 355, Math.max(200, owner.getHeight() - 220)));
        var footer = new VBox(7, FoodCard.label(messages.text("feedback.shared"), "catalog-muted"), status, submit);
        footer.getStyleClass().add("assistance-body");
        pane.setContent(new VBox(6, scroll, footer));
    }
}
