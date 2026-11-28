package com.roseroots.customer.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.services.MenuSearch.Filters;
import com.roseroots.customer.services.MenuSearch.Sort;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.state.CustomerState;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

class MenuNavigationTest {
    @Test
    void preservesBrowsingPreferencesAndServiceModeOnDetailsRoundTrip() {
        var state = new CustomerState();
        state.selectServiceMode(ServiceMode.TAKEAWAY);
        state.setLocale(Locale.forLanguageTag("ar"));
        var browse = state.menu();
        browse.setCatalog(new MockMenuService().loadCatalog().toCompletableFuture().join());
        browse.selectCategory("drinks");
        browse.selectSubcategory("hot-drinks");
        var filters = new Filters("coffee", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Sort.NAME);
        browse.setFilters(filters);

        var host = new StackPane();
        var details = new StackPane();
        var navigator = new Navigator(host);
        navigator.register(Route.MENU, StackPane::new);
        navigator.register(Route.DISH_DETAILS, () -> {
            assertEquals("ethiopian-coffee", browse.itemId());
            return details;
        });

        navigator.show(Route.MENU);
        var firstMenu = host.getChildren().getFirst();
        browse.selectItem("ethiopian-coffee");
        navigator.show(Route.DISH_DETAILS);
        assertSame(details, host.getChildren().getFirst());
        assertNull(firstMenu.getParent());
        navigator.show(Route.MENU);

        assertEquals(filters, browse.filters());
        assertEquals("hot-drinks", browse.subcategoryId());
        assertEquals(ServiceMode.TAKEAWAY, state.serviceMode());
        assertEquals("ar", state.locale().getLanguage());
    }

    @Test
    void resetAndReloadClearInvalidSubcategoryWithoutRemovingOtherBrowsingPreferences() {
        var state = new CustomerState().menu();
        var catalog = new MockMenuService().loadCatalog().toCompletableFuture().join();
        state.setCatalog(catalog);
        state.selectCategory("drinks");
        state.selectSubcategory("hot-drinks");

        state.setCatalog(new MenuCatalog(catalog.childrenOf(null), List.of(), false));
        assertEquals("drinks", state.categoryId());
        assertNull(state.subcategoryId());
        state.resetFilters();
        assertEquals("drinks", state.categoryId());
        assertEquals(Filters.defaults(), state.filters());
    }
}
