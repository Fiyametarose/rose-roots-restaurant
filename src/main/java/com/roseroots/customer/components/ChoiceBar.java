package com.roseroots.customer.components;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;

public final class ChoiceBar<T> extends FlowPane {
    public ChoiceBar(List<T> options, T selected, Function<T, String> label, Consumer<T> onSelect) {
        setAlignment(Pos.CENTER);
        getStyleClass().add("choice-bar");
        var group = new ToggleGroup();
        for (T option : options) {
            var button = new ToggleButton(label.apply(option));
            button.setToggleGroup(group);
            button.setSelected(Objects.equals(option, selected));
            button.setOnAction(event -> {
                button.setSelected(true);
                onSelect.accept(option);
            });
            getChildren().add(button);
        }
    }
}
