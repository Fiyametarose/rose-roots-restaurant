package com.roseroots.server.catalog;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.roseroots.server.catalog.CatalogData.*;

@RestController
@Profile("catalog")
class CatalogController {
    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/menu")
    Snapshot menu() {
        return catalog.snapshot(true);
    }

    @GetMapping("/api/v1/categories")
    List<Category> categories() {
        return menu().categories();
    }

    @GetMapping("/api/v1/menu-items")
    List<Dish> items() {
        return menu().items();
    }

    @GetMapping("/api/v1/admin/catalog")
    Snapshot management() {
        return catalog.snapshot(false);
    }

    @PostMapping("/api/v1/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    Category createCategory(@Valid @RequestBody CategoryInput input) {
        return catalog.saveCategory(null, input);
    }

    @PatchMapping("/api/v1/admin/categories/{id}")
    Category updateCategory(@PathVariable("id") UUID id, @Valid @RequestBody CategoryInput input) {
        return catalog.saveCategory(id, input);
    }

    @PostMapping("/api/v1/admin/menu-items")
    @ResponseStatus(HttpStatus.CREATED)
    Dish createDish(@Valid @RequestBody DishInput input, Principal manager) {
        return catalog.saveDish(null, input, manager.getName());
    }

    @PatchMapping("/api/v1/admin/menu-items/{id}")
    Dish updateDish(@PathVariable("id") UUID id, @Valid @RequestBody DishInput input, Principal manager) {
        return catalog.saveDish(id, input, manager.getName());
    }

    @PatchMapping("/api/v1/admin/menu-items/{id}/trading")
    Dish updateTrading(@PathVariable("id") UUID id, @Valid @RequestBody TradingUpdate input, Principal manager) {
        return catalog.updateTrading(id, input, manager.getName());
    }
}
