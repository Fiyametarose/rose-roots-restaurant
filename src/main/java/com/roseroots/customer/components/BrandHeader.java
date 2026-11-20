package com.roseroots.customer.components;

import com.roseroots.customer.models.RestaurantBrand;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class BrandHeader extends VBox {
    public BrandHeader(RestaurantBrand brand) {
        setAlignment(Pos.CENTER);
        if (brand.logo() != null) {
            boolean remote = brand.logo().getProtocol().startsWith("http");
            var mark = new ImageView(new Image(brand.logo().toExternalForm(), remote));
            mark.setFitHeight(72);
            mark.setPreserveRatio(true);
            getChildren().add(mark);
        }
        var name = new Label(brand.name());
        name.setWrapText(true);
        name.getStyleClass().add("brand-name");
        var descriptor = new Label(brand.descriptor().toUpperCase(Locale.ROOT));
        descriptor.getStyleClass().add("brand-descriptor");
        var subtitle = new HBox(12, rule(), descriptor, rule());
        subtitle.setAlignment(Pos.CENTER);
        subtitle.getStyleClass().add("brand-subtitle");
        getChildren().addAll(name, subtitle);
    }

    private static Region rule() {
        var rule = new Region();
        rule.getStyleClass().add("brand-rule");
        return rule;
    }
}
