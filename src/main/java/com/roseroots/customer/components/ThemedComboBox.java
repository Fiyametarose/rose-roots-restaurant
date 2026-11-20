package com.roseroots.customer.components;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Skin;
import javafx.scene.control.skin.ComboBoxListViewSkin;

public final class ThemedComboBox<T> extends ComboBox<T> {
    private static final String THEME = ThemedComboBox.class.getResource("/styles/theme.css").toExternalForm();

    @Override
    protected Skin<?> createDefaultSkin() {
        return new ComboBoxListViewSkin<>(this) {
            @Override
            public Node getPopupContent() {
                Node content = super.getPopupContent();
                if (content instanceof Parent parent && !parent.getStylesheets().contains(THEME)) {
                    parent.getStyleClass().add("rose-dropdown-list");
                    parent.getStylesheets().add(THEME);
                }
                return content;
            }
        };
    }
}
