package com.roseroots.server.catalog;

import com.roseroots.server.admin.DashboardSnapshot;
import com.roseroots.server.admin.DashboardSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("catalog")
class CatalogDashboardSource implements DashboardSource {
    private final CatalogService catalog;

    CatalogDashboardSource(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public DashboardSnapshot snapshot() {
        var soldOut = catalog.snapshot(true).items().stream()
                .filter(dish -> dish.availability() == CatalogData.Availability.SOLD_OUT)
                .map(CatalogData.Dish::name).toList();
        return new DashboardSnapshot(null, null, null, null, null, soldOut, null);
    }
}
