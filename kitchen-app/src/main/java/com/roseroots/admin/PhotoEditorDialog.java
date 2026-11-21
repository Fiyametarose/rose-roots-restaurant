package com.roseroots.admin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import static com.roseroots.admin.CatalogControls.*;

final class PhotoEditorDialog {
    private record Aspect(String label, double ratio) {
        @Override public String toString() { return label; }
    }

    private static final List<Aspect> ASPECTS = List.of(new Aspect("Customer card · 1.87:1", 1.87),
            new Aspect("Dish details · 1.53:1", 1.53), new Aspect("Square · 1:1", 1),
            new Aspect("Original proportions", 0));

    private PhotoEditorDialog() {}

    static Optional<PhotoDraft> open(Window owner, BufferedImage source, PhotoProcessing.Settings initial) {
        var dialog = new Dialog<PhotoDraft>();
        dialog.initOwner(owner);
        dialog.setTitle("Rose & Roots | Image Editor");
        dialog.setResizable(true);
        var pane = dialog.getDialogPane();
        pane.getStylesheets().add(PhotoEditorDialog.class.getResource("/admin/admin.css").toExternalForm());
        pane.getStyleClass().add("photo-dialog");
        var apply = new ButtonType("Apply", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(apply, ButtonType.CANCEL);
        dialog.setResultConverter(button -> null);

        var original = new ImageView();
        var edited = new ImageView();
        for (var preview : List.of(original, edited)) {
            preview.setPreserveRatio(true);
            preview.setFitHeight(235);
        }
        var left = previewPanel("Original preview", original);
        var right = previewPanel("Edited preview", edited);
        var previews = new HBox(14, left, right);
        for (var preview : List.of(left, right)) {
            preview.setMinWidth(0);
            HBox.setHgrow(preview, Priority.ALWAYS);
        }
        original.fitWidthProperty().bind(left.widthProperty().subtract(24));
        edited.fitWidthProperty().bind(right.widthProperty().subtract(24));

        var aspect = new ComboBox<Aspect>(javafx.collections.FXCollections.observableArrayList(ASPECTS));
        aspect.setValue(ASPECTS.stream().filter(option -> option.ratio == initial.aspect()).findFirst().orElse(ASPECTS.getFirst()));
        var zoom = slider(1, 4, initial.zoom());
        var horizontal = slider(-1, 1, initial.x());
        var vertical = slider(-1, 1, initial.y());
        var brightness = slider(-.50, .50, initial.brightness());
        var contrast = slider(.25, 2, initial.contrast());
        var turns = new javafx.beans.property.SimpleIntegerProperty(initial.turns());
        var rotation = label("", "muted");
        rotation.textProperty().bind(turns.asString("Rotation %d quarter turns"));
        var status = label("Preparing previews…", "feedback");
        var applyButton = (Button) pane.lookupButton(apply);
        applyButton.setDisable(true);
        var reset = button("Reset to Original", () -> {
            aspect.setValue(ASPECTS.getLast());
            zoom.setValue(1);
            horizontal.setValue(0);
            vertical.setValue(0);
            brightness.setValue(0);
            contrast.setValue(1);
            turns.set(0);
        });
        var tools = new FlowPane(14, 10, field("Crop aspect ratio", aspect), field("Zoom", zoom),
                field("Reposition left / right", horizontal), field("Reposition up / down", vertical),
                field("Brightness", brightness), field("Contrast", contrast),
                field("Rotation", new HBox(8, button("Rotate Left", () -> turns.set(Math.floorMod(turns.get() - 1, 4))),
                        button("Rotate Right", () -> turns.set(Math.floorMod(turns.get() + 1, 4))), rotation)), reset);
        var content = content(label("Image Editor", "heading"), previews, tools, status);
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(880);
        scroll.setPrefViewportHeight(565);
        pane.setContent(scroll);

        java.util.function.Supplier<PhotoProcessing.Settings> settings = () -> new PhotoProcessing.Settings(
                aspect.getValue().ratio, zoom.getValue(), horizontal.getValue(), vertical.getValue(),
                turns.get(), brightness.getValue(), contrast.getValue());
        var debounce = new PauseTransition(Duration.millis(110));
        long[] generation = {0};
        boolean[] rendering = {false};
        Runnable modified = () -> {
            generation[0]++;
            debounce.playFromStart();
        };
        debounce.setOnFinished(event -> {
            if (rendering[0] || !dialog.isShowing()) {
                return;
            }
            rendering[0] = true;
            long revision = generation[0];
            var adjustment = settings.get();
            AdminWorkspace.background(() -> preview(PhotoProcessing.jpeg(PhotoProcessing.render(source, adjustment, 650)), 650),
                    (image, failure) -> {
                        rendering[0] = false;
                        if (!dialog.isShowing()) {
                            return;
                        }
                        if (revision != generation[0]) {
                            debounce.playFromStart();
                        } else if (failure == null) {
                            edited.setImage(image);
                            status.setText("");
                            applyButton.setDisable(false);
                        } else {
                            status.setText("Could not render the preview.");
                        }
                    });
        });
        aspect.valueProperty().addListener(observable -> modified.run());
        turns.addListener(observable -> modified.run());
        for (var slider : List.of(zoom, horizontal, vertical, brightness, contrast)) {
            slider.valueProperty().addListener(observable -> modified.run());
        }
        dialog.setOnShown(event -> {
            AdminWorkspace.background(() -> preview(PhotoProcessing.jpeg(
                    PhotoProcessing.render(source, PhotoProcessing.ORIGINAL, 650)), 650), (image, failure) -> {
                if (dialog.isShowing() && failure == null) {
                    original.setImage(image);
                }
            });
            modified.run();
        });
        applyButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            applyButton.setDisable(true);
            tools.setDisable(true);
            debounce.stop();
            generation[0]++;
            status.setText("Preparing the edited photo…");
            var adjustment = settings.get();
            AdminWorkspace.background(() -> new PhotoDraft(source, adjustment,
                    PhotoProcessing.jpeg(PhotoProcessing.render(source, adjustment, 1600))), (draft, failure) -> {
                if (!dialog.isShowing()) {
                    return;
                }
                if (failure == null) {
                    dialog.setResult(draft);
                    dialog.close();
                } else {
                    status.setText("Could not apply the photo edits.");
                    tools.setDisable(false);
                    applyButton.setDisable(false);
                }
            });
        });
        dialog.setOnHidden(event -> debounce.stop());
        return dialog.showAndWait();
    }

    static Image preview(byte[] bytes, int edge) {
        return new Image(new ByteArrayInputStream(bytes), edge, edge, true, true);
    }

    private static VBox previewPanel(String title, ImageView image) {
        var box = content(label(title, "field-label"), image);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(420);
        box.setMinHeight(285);
        box.getStyleClass().add("photo-preview");
        return box;
    }

    private static Slider slider(double min, double max, double value) {
        var slider = new Slider(min, max, value);
        slider.setPrefWidth(172);
        slider.setShowTickLabels(true);
        slider.setMajorTickUnit((max - min) / 2);
        return slider;
    }
}
