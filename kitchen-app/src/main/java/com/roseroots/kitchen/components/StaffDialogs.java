package com.roseroots.kitchen.components;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Window;

public final class StaffDialogs {
    private StaffDialogs() {}

    public static void theme(Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        var owner = dialog.getOwner();
        if (owner != null && owner.getScene() != null) {
            pane.getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        if (pane.getStylesheets().isEmpty()) {
            pane.getStylesheets().add(StaffDialogs.class.getResource("/kitchen/board.css").toExternalForm());
        }
        if (pane.getContent() == null && pane.getContentText() != null) {
            var message = new Label(pane.getContentText());
            message.setWrapText(true);
            pane.setContent(message);
        }
        if (dialog instanceof Alert alert) {
            boolean attention = alert.getAlertType() == Alert.AlertType.WARNING || alert.getAlertType() == Alert.AlertType.ERROR;
            indicator(dialog, attention ? "!" : alert.getAlertType() == Alert.AlertType.CONFIRMATION ? "?" : "i", attention);
        } else if (dialog instanceof TextInputDialog) {
            indicator(dialog, "?", false);
        }
        var bounds = usableBounds(owner);
        pane.setMaxWidth(bounds.getWidth() - 32);
        pane.setMaxHeight(bounds.getHeight() - 32);
        if (pane.getPrefWidth() < 0 && pane.getContent() instanceof Label) {
            pane.setPrefWidth(Math.min(480, pane.getMaxWidth()));
        }
        for (var type : pane.getButtonTypes()) {
            if (type.getButtonData().isCancelButton()) {
                pane.lookupButton(type).getStyleClass().add("dialog-cancel");
            }
        }
    }

    private static void indicator(Dialog<?> dialog, String text, boolean attention) {
        var label = new Label(text);
        label.getStyleClass().add("dialog-indicator");
        if (attention) label.getStyleClass().add("attention");
        label.setAccessibleRole(javafx.scene.AccessibleRole.NODE);
        dialog.setGraphic(label);
    }

    public static Rectangle2D usableBounds(Window owner) {
        return (owner == null ? Screen.getPrimary() : Screen.getScreensForRectangle(
                owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight()).stream()
                .findFirst().orElse(Screen.getPrimary())).getVisualBounds();
    }

    public static void destructive(Dialog<?> dialog, ButtonType confirmation) {
        var pane = dialog.getDialogPane();
        var action = (Button) pane.lookupButton(confirmation);
        action.getStyleClass().add("dialog-destructive");
        action.setDefaultButton(false);
    }
}
