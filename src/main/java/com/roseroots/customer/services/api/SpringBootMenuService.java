package com.roseroots.customer.services.api;

import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.models.MenuCategory;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.services.MenuService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Adapter boundary for the agreed Spring Boot catalog endpoints. */
public final class SpringBootMenuService implements MenuService {
    private final CatalogClient client;

    public SpringBootMenuService(CatalogClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public CompletionStage<MenuCatalog> loadCatalog() {
        return client.categories().thenCombine(client.items(),
                (categories, items) -> new MenuCatalog(categories, items, false));
    }

    public interface CatalogClient {
        CompletionStage<List<MenuCategory>> categories();

        CompletionStage<List<MenuItem>> items();
    }
}
