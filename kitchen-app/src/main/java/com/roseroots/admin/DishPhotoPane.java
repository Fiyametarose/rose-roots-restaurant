package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import static com.roseroots.admin.CatalogControls.*;

final class DishPhotoPane extends VBox {
    final SimpleBooleanProperty busy = new SimpleBooleanProperty();
    private final CatalogWorkspace workspace;
    private final String savedReference;
    private final Label feedback = label("", "feedback");
    private final StackPane preview = new StackPane();
    private PhotoDraft draft;
    private boolean removed;

    DishPhotoPane(CatalogWorkspace workspace, JsonObject dish) {
        super(9);
        this.workspace = workspace;
        savedReference = text(dish, "imageReference");
        preview.getStyleClass().add("photo-preview");
        preview.setMinHeight(198);
        preview.getChildren().setAll(workspace.photoPreview(dish, 196));
        feedback.setText(savedReference.isEmpty() ? "No photo saved." : "Saved photo");
        var choose = button("Choose Photo", this::choose);
        var edit = button("Open Image Editor", this::edit);
        var remove = button("Remove Photo", () -> {
            draft = null;
            removed = true;
            preview.getChildren().setAll(label("No photo", "muted"));
            choose.setText("Choose Photo");
            edit.setDisable(true);
            removeState();
        });
        choose.setText(savedReference.isEmpty() ? "Choose Photo" : "Replace Photo");
        edit.setDisable(savedReference.isEmpty());
        remove.setDisable(savedReference.isEmpty());
        remove.disableProperty().bind(edit.disableProperty());
        var actions = new FlowPane(8, 7, choose, edit, remove);
        actions.disableProperty().bind(busy);
        getChildren().addAll(preview, actions, feedback);
        draftChanged = () -> {
            choose.setText("Replace Photo");
            edit.setDisable(false);
        };
    }

    private final Runnable draftChanged;

    private void choose() {
        var chooser = new FileChooser();
        chooser.setTitle("Choose a food photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JPEG and PNG photos",
                "*.jpg", "*.jpeg", "*.png", "*.JPG", "*.JPEG", "*.PNG"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        load(file.toPath());
    }

    void load(java.nio.file.Path path) {
        busy.set(true);
        feedback.setText("Validating and loading photo…");
        AdminWorkspace.background(() -> {
            if (!Files.isRegularFile(path) || Files.size(path) > PhotoProcessing.BYTE_LIMIT) {
                throw new IllegalArgumentException("Choose a JPEG or PNG file no larger than 8 MiB.");
            }
            try (var input = Files.newInputStream(path)) {
                var source = PhotoProcessing.read(input);
                return new PhotoDraft(source, PhotoProcessing.ORIGINAL,
                        PhotoProcessing.jpeg(PhotoProcessing.render(source, PhotoProcessing.ORIGINAL, 1600)));
            }
        }, (selected, failure) -> {
            busy.set(false);
            if (failure == null) {
                showDraft(selected, "Photo selected. Save Dish to upload and publish it.");
            } else {
                feedback.setText(failure instanceof IOException ? "Could not read the selected photo." : failure.getMessage());
            }
        });
    }

    private void edit() {
        if (draft != null) {
            open(draft.original, draft.settings == PhotoProcessing.ORIGINAL ? PhotoProcessing.CARD : draft.settings);
        } else if (!removed && !savedReference.isEmpty()) {
            busy.set(true);
            feedback.setText("Loading saved photo…");
            AdminWorkspace.background(() -> workspace.readPhoto(savedReference), (original, failure) -> {
                busy.set(false);
                if (failure == null && getScene() != null) {
                    feedback.setText("Saved photo");
                    open(original, PhotoProcessing.CARD);
                } else if (failure != null) {
                    feedback.setText("Could not open the saved photo. Choose a replacement or retry.");
                }
            });
        }
    }

    private void open(java.awt.image.BufferedImage original, PhotoProcessing.Settings settings) {
        PhotoEditorDialog.open(getScene().getWindow(), original, settings).ifPresent(
                edited -> showDraft(edited, "Edits applied to draft. Save Dish to publish them."));
    }

    private void showDraft(PhotoDraft selected, String status) {
        draft = selected;
        removed = false;
        var image = new ImageView(PhotoEditorDialog.preview(selected.image, 460));
        image.setPreserveRatio(true);
        image.setFitHeight(195);
        image.fitWidthProperty().bind(preview.widthProperty().subtract(12));
        preview.getChildren().setAll(image);
        draftChanged.run();
        feedback.setText(status);
    }

    private void removeState() {
        feedback.setText("Photo will be removed when you save.");
    }

    PhotoDraft draft() { return draft; }
    String retainedReference() { return removed ? "" : savedReference; }
}
