package com.roseroots.customer.components;

import javafx.scene.shape.Path;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

public final class InterfaceIcon extends Path {
    public enum Kind { STARTER, MAIN, DESSERT, DRINK, SEARCH, LEAF, GLOBE, CART, BACK, HOME, ORDERS, CUTLERY, NAPKINS, HELP, EDIT }

    public InterfaceIcon(Kind kind) {
        getStyleClass().addAll("interface-icon", "icon-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        setStrokeLineJoin(StrokeLineJoin.ROUND);
        setStrokeLineCap(StrokeLineCap.ROUND);
        setMouseTransparent(true);
        switch (kind) {
            case STARTER -> {
                line(2, 11, 20, 11);
                move(2, 11); curve(3, 19, 11, 19); curve(19, 19, 20, 11);
                line(7, 19, 6, 21, 16, 21, 15, 19);
                steam(7); steam(12); steam(17);
            }
            case MAIN -> {
                line(2, 18, 21, 18, 21, 21, 2, 21, 2, 18);
                move(4, 18); curve(4, 8, 11.5, 8); curve(19, 8, 19, 18);
                move(11.5, 8); curve(8, 4, 11.5, 3); curve(15, 4, 11.5, 8);
            }
            case DESSERT -> {
                line(2, 11, 13, 4, 21, 11, 21, 21, 2, 21, 2, 11, 21, 11);
                line(2, 16, 21, 16);
                move(11, 6); curve(13, 10, 16, 7);
            }
            case DRINK -> {
                line(4, 8, 20, 8, 17, 22, 7, 22, 4, 8);
                line(12, 13, 15, 2, 21, 2);
                line(6, 15, 18, 15);
            }
            case SEARCH -> {
                move(15, 9); curve(15, 15, 9, 15); curve(3, 15, 3, 9);
                curve(3, 3, 9, 3); curve(15, 3, 15, 9);
                line(13.5, 13.5, 20, 20);
            }
            case LEAF -> {
                move(4, 19); curve(1, 7, 20, 3); curve(19, 21, 7, 19);
                line(3, 22, 15, 9);
            }
            case GLOBE -> {
                move(21, 12); curve(21, 21, 12, 21); curve(3, 21, 3, 12);
                curve(3, 3, 12, 3); curve(21, 3, 21, 12);
                move(12, 3); curve(4, 12, 12, 21);
                move(12, 3); curve(20, 12, 12, 21);
                line(4, 12, 20, 12);
            }
            case CART -> {
                line(2, 3, 5, 3, 9, 16, 19, 16);
                line(7, 7, 22, 7, 19, 13, 8, 13);
                line(10, 20, 11, 20);
                line(17, 20, 18, 20);
            }
            case CUTLERY -> {
                line(3, 4, 6, 9, 9, 11, 19, 21);
                line(6, 3, 10, 8, 9, 11);
                line(18, 4, 13, 9, 13, 13, 4, 21);
                line(21, 6, 16, 11, 13, 13);
            }
            case NAPKINS -> {
                line(3, 11, 21, 11, 21, 20, 3, 20, 3, 11);
                line(7, 11, 5, 5, 9, 7, 13, 4, 18, 6, 16, 11);
                line(8, 14, 16, 14);
            }
            case HELP -> {
                move(5, 17); curve(0, 12, 4, 6); curve(8, 1, 17, 4);
                curve(25, 7, 20, 15); curve(17, 19, 10, 18);
                line(10, 18, 4, 21, 5, 17);
                move(10, 8); curve(13, 6, 15, 9); curve(16, 11, 12, 12);
                line(12, 12, 12, 13);
                line(12, 15, 12.1, 15);
            }
            case EDIT -> {
                line(4, 21, 6, 15, 17, 4, 21, 8, 10, 19, 4, 21);
                line(6, 15, 10, 19);
            }
            case ORDERS -> {
                line(5, 3, 19, 3, 19, 21, 15, 19, 12, 21, 9, 19, 5, 21, 5, 3);
                line(8, 7, 16, 7);
                line(8, 11, 16, 11);
                line(8, 15, 13, 15);
            }
            case BACK -> {
                line(13, 4, 5, 12, 13, 20);
                line(5, 12, 21, 12);
            }
            case HOME -> {
                line(3, 11, 12, 3, 21, 11);
                line(6, 9, 6, 21, 18, 21, 18, 9);
                line(10, 21, 10, 15, 14, 15, 14, 21);
            }
        }
    }

    private void steam(double x) {
        move(x, 9);
        curve(x - 3, 7, x, 4);
        curve(x + 1, 3, x, 2);
    }

    private void line(double... coordinates) {
        move(coordinates[0], coordinates[1]);
        for (int i = 2; i < coordinates.length; i += 2) {
            getElements().add(new LineTo(coordinates[i], coordinates[i + 1]));
        }
    }

    private void move(double x, double y) {
        getElements().add(new MoveTo(x, y));
    }

    private void curve(double cx, double cy, double x, double y) {
        getElements().add(new QuadCurveTo(cx, cy, x, y));
    }
}
