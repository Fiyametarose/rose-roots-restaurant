package com.roseroots.admin;

import static com.roseroots.admin.CatalogControls.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

final class BrandingWorkspace extends VBox {
    private final AdminApi api;
    private final Runnable expired;
    private final Map<String, TextInputControl> inputs = new LinkedHashMap<>();
    private final Map<String, JsonObject> palettes = new LinkedHashMap<>();
    private final ComboBox<String> theme = options();
    private final Label status = label("", "muted");
    private final Label logoStatus = label("", "muted");
    private final Label previewName = label("", "branding-preview-name");
    private final Label previewTagline = label("", "branding-preview-tagline");
    private final Label previewDescription = label("", "muted");
    private final Label previewContact = label("", "muted");
    private final Label operations = label("", "muted");
    private final ImageView logo = new ImageView();
    private final VBox preview = new VBox(8);
    private final VBox form;
    private final Button reload = button("Reload saved settings", this::load);
    private final FlowPane logoActions = new FlowPane(8, 8, button("Choose or replace logo", this::chooseLogo),
            secondary("Remove logo", () -> selectLogo("")),
            secondary("Use default logo", () -> selectLogo("/api/v1/settings/logo/default")));
    private final FlowPane actions = new FlowPane(8, 8, button("Save Changes", this::save),
            secondary("Reset Unsaved Changes", this::reset));
    private JsonObject saved;
    private String logoReference;
    private LogoDraft draft;

    BrandingWorkspace(AdminApi api, Runnable expired) {
        this.api = api;
        this.expired = expired;
        logo.setFitWidth(200);
        logo.setFitHeight(100);
        logo.setPreserveRatio(true);
        var logoBox = new StackPane(logo);
        logoBox.setMinHeight(104);
        preview.getChildren().addAll(logoBox, previewName, previewTagline, previewDescription, previewContact);
        preview.getStyleClass().add("branding-preview");
        preview.setAlignment(Pos.CENTER);
        theme.valueProperty().addListener((observable, before, after) -> updatePreview());

        form = content(field("Restaurant name", input("name", "Required", 1)),
                field("Tagline", input("tagline", "", 1)),
                field("Description", input("description", "", 2)),
                field("Contact telephone", input("telephone", "", 1)),
                field("Contact email", input("email", "", 1)),
                field("Restaurant address", input("address", "", 2)),
                field("Theme preset", theme));
        var editable = panel("Editable branding settings", form);
        var publicPreview = panel("Public preview", content(preview, field("Restaurant logo", logoActions), logoStatus));
        var readOnly = panel("Operational settings · read-only", content(operations,
                label("Billing policies and language management are outside branding settings.", "muted")));
        var side = new VBox(14, publicPreview, readOnly);
        editable.setMinWidth(0);
        side.setMinWidth(0);
        reload.getStyleClass().add("secondary");
        setSpacing(12);
        actions.getChildren().add(reload);
        getChildren().addAll(label("Restaurant Branding", "heading"),
                label("Manage the identity and contact details shown to customers.", "muted"),
                actions, status, paired(editable, side));
        load();
    }

    private TextInputControl input(String key, String prompt, int rows) {
        TextInputControl input;
        if (rows == 1) {
            input = new TextField();
        } else {
            var area = new TextArea();
            area.setWrapText(true);
            area.setPrefRowCount(rows);
            input = area;
        }
        input.setPromptText(prompt);
        input.textProperty().addListener((observable, before, after) -> updatePreview());
        inputs.put(key, input);
        return input;
    }

    private Button secondary(String title, Runnable action) {
        var control = button(title, action);
        control.getStyleClass().add("secondary");
        return control;
    }

    private void load() {
        busy("Loading restaurant settings…");
        AdminWorkspace.background(api::settings, (value, failure) -> {
            if (failure == null) {
                saved = value;
                restore();
                message("Saved settings loaded.", false);
            } else {
                failed(failure);
            }
        });
    }

    private void restore() {
        var brand = saved.getAsJsonObject("brand");
        palettes.clear();
        rows(saved, "themes").forEach(palette -> palettes.put(text(palette, "preset"), palette));
        theme.getItems().setAll(palettes.keySet().stream().map(CatalogControls::words).toList());
        theme.setValue(words(text(brand.getAsJsonObject("theme"), "preset")));
        inputs.forEach((key, input) -> input.setText(text(brand, key)));
        operations.setText("Currency  " + text(brand, "currency")
                + "\nService modes  " + displayList(brand, "serviceModes")
                + "\nLanguages  " + displayList(brand, "languages"));
        selectLogo(text(brand, "logoReference"));
    }

    private static String displayList(JsonObject brand, String key) {
        var values = strings(brand, key);
        return values.isEmpty() ? "Managed by the client" : String.join(", ", values.stream()
                .map(value -> key.equals("serviceModes") ? words(value) : value).toList());
    }

    private void reset() {
        restore();
        message("Unsaved changes reset.", false);
    }

    private void selectLogo(String reference) {
        draft = null;
        logoReference = reference;
        logo.setImage(null);
        String url = reference.isBlank() ? "" : api.imageUrl(reference);
        logoStatus.setText(url.isEmpty() ? "No logo selected." : "Loading logo…");
        if (!url.isEmpty()) {
            var image = new Image(url, 200, 100, true, true, true);
            logo.setImage(image);
            image.progressProperty().addListener((observable, before, after) -> {
                if (image == logo.getImage() && after.doubleValue() >= 1) {
                    logoStatus.setText(image.isError() ? "Logo could not be loaded." : "Logo ready.");
                }
            });
            if (image.getProgress() >= 1) {
                logoStatus.setText(image.isError() ? "Logo could not be loaded." : "Logo ready.");
            }
        }
    }

    private void chooseLogo() {
        var chooser = new FileChooser();
        chooser.setTitle("Choose a restaurant logo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JPEG or PNG", "*.jpg", "*.jpeg", "*.png"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        busy("Checking logo…");
        AdminWorkspace.background(() -> {
            try (var stream = Files.newInputStream(file.toPath())) {
                byte[] bytes = stream.readNBytes(PhotoProcessing.BYTE_LIMIT + 1);
                PhotoProcessing.read(new ByteArrayInputStream(bytes));
                return new LogoDraft(bytes);
            }
        }, (value, failure) -> {
            if (failure != null) {
                message("Choose a valid JPEG or PNG logo up to 8 MiB and 16 megapixels.", true);
                return;
            }
            var image = new Image(new ByteArrayInputStream(value.bytes), 200, 100, true, true);
            if (image.isError()) {
                message("The logo could not be opened.", true);
                return;
            }
            draft = value;
            logo.setImage(image);
            logoStatus.setText(file.getName() + " · ready to save");
            message("Logo preview updated.", false);
        });
    }

    private void updatePreview() {
        previewName.setText(value("name").isBlank() ? "Restaurant name" : value("name"));
        previewTagline.setText(value("tagline"));
        previewDescription.setText(value("description"));
        previewContact.setText(String.join("\n", java.util.stream.Stream.of(
                value("telephone"), value("email"), value("address")).filter(s -> !s.isBlank()).toList()));
        var palette = selectedPalette();
        preview.setStyle("-fx-background-color: " + color(palette, "background", "#F7F2E8")
                + "; -fx-border-color: " + color(palette, "accent", "#A77936") + ";");
        previewName.setStyle("-fx-text-fill: " + color(palette, "primary", "#0F3D29") + ";");
    }

    private JsonObject selectedPalette() {
        return palettes.entrySet().stream().filter(entry -> words(entry.getKey()).equals(theme.getValue()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String color(JsonObject palette, String key, String fallback) {
        String color = text(palette, key);
        return color.matches("#[0-9A-Fa-f]{6}") ? color : fallback;
    }

    private String value(String key) {
        var control = inputs.get(key);
        return control == null ? "" : control.getText().strip();
    }

    private void save() {
        if (value("name").isBlank()) {
            message("Enter the restaurant name.", true);
            inputs.get("name").requestFocus();
            return;
        }
        var update = new JsonObject();
        update.add("version", saved.get("version"));
        inputs.keySet().forEach(key -> update.addProperty(key, value(key)));
        update.addProperty("theme", text(selectedPalette(), "preset"));
        if (logoReference.isBlank()) {
            update.add("logoReference", JsonNull.INSTANCE);
        } else {
            update.addProperty("logoReference", logoReference);
        }
        LogoDraft pending = draft;
        busy("Saving restaurant settings…");
        AdminWorkspace.background(() -> {
            if (pending != null) {
                if (pending.uploadedReference == null) {
                    pending.uploadedReference = api.uploadLogo(pending.bytes);
                }
                update.addProperty("logoReference", pending.uploadedReference);
            }
            return api.saveSettings(update);
        }, (value, failure) -> {
            if (failure == null) {
                saved = value;
                restore();
                message("Restaurant settings saved.", false);
            } else {
                failed(failure);
            }
        });
    }

    private void busy(String content) {
        message(content, false);
        setEditing(false, false);
    }

    private void message(String content, boolean error) {
        status.setText(content);
        status.getStyleClass().setAll("label", error ? "feedback" : "muted");
        setEditing(saved != null, true);
    }

    private void setEditing(boolean enabled, boolean canReload) {
        actions.getChildren().forEach(action -> action.setDisable(action == reload ? !canReload : !enabled));
        form.setDisable(!enabled);
        logoActions.setDisable(!enabled);
    }

    private void failed(Exception failure) {
        if (failure instanceof AdminApi.Rejected rejected) {
            if (rejected.status == 401 || rejected.status == 403) {
                expired.run();
            } else if (!rejected.detail.isBlank()) {
                message(rejected.detail, true);
            } else {
                message("Restaurant settings are unavailable. Try reloading.", true);
            }
        } else {
            message("Disconnected from the Admin server. Check the connection and retry.", true);
        }
    }

    private static final class LogoDraft {
        final byte[] bytes;
        String uploadedReference;

        LogoDraft(byte[] bytes) {
            this.bytes = bytes;
        }
    }
}
