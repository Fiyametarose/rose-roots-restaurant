package com.roseroots.customer.components;

import com.roseroots.customer.models.ServiceMode;
import javafx.scene.layout.Region;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

public final class ServiceIcon extends Region {
    public ServiceIcon(ServiceMode mode) {
        var outline = new Path();
        outline.getStyleClass().add("service-icon");
        outline.setStrokeLineCap(StrokeLineCap.ROUND);
        outline.setStrokeLineJoin(StrokeLineJoin.ROUND);
        switch (mode) {
            case DINE_IN -> {
                trace(outline, 9, 22, 43, 22);
                trace(outline, 16, 23, 13, 39);
                trace(outline, 36, 23, 39, 39);
                trace(outline, 3, 18, 6, 31, 13, 31);
                trace(outline, 6, 31, 4, 40);
                trace(outline, 49, 18, 46, 31, 39, 31);
                trace(outline, 46, 31, 48, 40);
                trace(outline, 26, 21, 26, 11, 22, 8, 26, 5, 30, 8, 26, 11);
            }
            case TAKEAWAY -> {
                trace(outline, 12, 15, 40, 15, 45, 44, 7, 44, 12, 15);
                trace(outline, 19, 15, 19, 7, 33, 7, 33, 15);
                trace(outline, 20, 24, 32, 36);
                trace(outline, 32, 24, 20, 36);
            }
        }
        setPrefSize(52, 48);
        setMinSize(52, 48);
        getChildren().add(outline);
    }

    private static void trace(Path path, double x, double y, double... coordinates) {
        path.getElements().add(new MoveTo(x, y));
        for (int i = 0; i < coordinates.length; i += 2) {
            path.getElements().add(new LineTo(coordinates[i], coordinates[i + 1]));
        }
    }
}
