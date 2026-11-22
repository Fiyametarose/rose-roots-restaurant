package com.roseroots.customer.components;

import com.roseroots.customer.models.RestaurantBrand;
import com.roseroots.customer.services.RestaurantSettings;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class BrandHeader extends VBox {
    private final ImageView mark = new ImageView();
    private final Label name = new Label();
    private final Label descriptor = new Label();
    private String logo;

    public BrandHeader(RestaurantSettings settings) {
        setAlignment(Pos.CENTER);
        mark.setFitHeight(72);
        mark.setPreserveRatio(true);
        mark.managedProperty().bind(mark.visibleProperty());
        name.setWrapText(true);
        name.getStyleClass().add("brand-name");
        descriptor.getStyleClass().add("brand-descriptor");
        var subtitle = new HBox(12, rule(), descriptor, rule());
        subtitle.setAlignment(Pos.CENTER);
        subtitle.getStyleClass().add("brand-subtitle");
        getChildren().addAll(mark, name, subtitle);
        render(settings.brand());
        settings.observeWhileAttached(this, () -> render(settings.brand()));
    }

    private void render(RestaurantBrand brand) {
        name.setText(brand.name());
        descriptor.setText(brand.descriptor().toUpperCase(Locale.ROOT));
        String reference = brand.logo() == null ? null : brand.logo().toExternalForm();
        if (!java.util.Objects.equals(logo, reference)) {
            logo = reference;
            mark.setImage(reference == null ? null : new Image(reference, brand.logo().getProtocol().startsWith("http")));
        }
        mark.setVisible(reference != null);
    }

    private static Region rule() {
        var rule = new Region();
        rule.getStyleClass().add("brand-rule");
        return rule;
    }
}
