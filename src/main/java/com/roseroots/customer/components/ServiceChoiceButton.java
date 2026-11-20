package com.roseroots.customer.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class ServiceChoiceButton extends Button {
    private final Label title = new Label();
    private final Label description = new Label();

    public ServiceChoiceButton(Node icon, boolean primary, Runnable action) {
        title.getStyleClass().add("service-title");
        description.getStyleClass().add("service-description");
        var arrow = new Label("›");
        arrow.getStyleClass().add("service-arrow");
        var labels = new VBox(3, title, description);
        var graphic = new HBox(12, icon, labels, arrow);
        graphic.setAlignment(Pos.CENTER);
        getStyleClass().addAll("service-choice", primary ? "action-primary" : "action-secondary");
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setGraphic(graphic);
        setOnAction(event -> action.run());
    }

    public void setCopy(String heading, String detail) {
        title.setText(heading);
        description.setText(detail);
        setAccessibleText(heading + ". " + detail);
    }
}
