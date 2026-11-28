package com.roseroots.customer.services;

import com.roseroots.customer.models.MenuCatalog;
import java.util.concurrent.CompletionStage;

public interface MenuService {
    /** Returns a new snapshot. Implementations keep blocking I/O off the JavaFX thread. */
    CompletionStage<MenuCatalog> loadCatalog();

    default boolean refreshWhileVisible() { return false; }
}
