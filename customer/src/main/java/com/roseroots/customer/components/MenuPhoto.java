package com.roseroots.customer.components;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

public final class MenuPhoto extends StackPane {
    private static final int CACHE_CAPACITY = 48;
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(CACHE_CAPACITY, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    public MenuPhoto(String source, String fallback, double height) {
        getStyleClass().add("catalog-photo");
        setMinHeight(height);
        setPrefHeight(height);
        setMaxHeight(height);
        setMinWidth(0);
        setPrefWidth(0);
        var noPhoto = new Label(fallback);
        getChildren().add(noPhoto);
        if (source == null || source.isBlank()) {
            return;
        }
        URL bundled = source.startsWith("/") ? MenuPhoto.class.getResource(source) : null;
        String url = bundled == null ? source : bundled.toExternalForm();
        Image image;
        try {
            image = CACHE.computeIfAbsent(url, key -> new Image(key, true));
        } catch (IllegalArgumentException invalidSource) {
            return;
        }
        var view = new ImageView(image);
        view.fitWidthProperty().bind(widthProperty());
        view.fitHeightProperty().bind(heightProperty());
        view.visibleProperty().bind(image.progressProperty().greaterThanOrEqualTo(1).and(image.errorProperty().not()));
        noPhoto.visibleProperty().bind(view.visibleProperty().not());
        var clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        view.setClip(clip);
        Runnable update = () -> crop(view);
        widthProperty().addListener(observable -> update.run());
        heightProperty().addListener(observable -> update.run());
        if (image.getProgress() < 1 && !image.isError()) {
            image.progressProperty().addListener(new ChangeListener<Number>() {
                @Override
                public void changed(ObservableValue<? extends Number> property, Number previous, Number value) {
                    if (value.doubleValue() >= 1 || image.isError()) {
                        property.removeListener(this);
                        update.run();
                    }
                }
            });
        }
        getChildren().add(view);
        update.run();
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public void fitAspect(double aspect, double minimumHeight, double maximumHeight) {
        prefHeightProperty().bind(Bindings.createDoubleBinding(() ->
                Math.clamp(getWidth() / aspect, minimumHeight, maximumHeight), widthProperty()));
        minHeightProperty().bind(prefHeightProperty());
        maxHeightProperty().bind(prefHeightProperty());
    }

    private void crop(ImageView view) {
        var image = view.getImage();
        if (image.getWidth() <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        double ratio = getWidth() / getHeight();
        double width = Math.min(image.getWidth(), image.getHeight() * ratio);
        double height = Math.min(image.getHeight(), image.getWidth() / ratio);
        view.setViewport(new Rectangle2D((image.getWidth() - width) / 2,
                (image.getHeight() - height) / 2, width, height));
    }
}
