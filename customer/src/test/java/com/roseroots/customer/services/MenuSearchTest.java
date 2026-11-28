package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roseroots.customer.models.LocalizedText;
import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.MenuItem.Allergen;
import com.roseroots.customer.models.MenuItem.Availability;
import com.roseroots.customer.models.MenuItem.Cuisine;
import com.roseroots.customer.models.MenuItem.Diet;
import com.roseroots.customer.models.MenuItem.FoodSafety;
import com.roseroots.customer.models.MenuItem.Highlight;
import com.roseroots.customer.models.MenuItem.Spice;
import com.roseroots.customer.services.MenuSearch.Filters;
import com.roseroots.customer.services.MenuSearch.Sort;
import com.roseroots.customer.services.mock.MockMenuService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuSearchTest {
    private final MenuCatalog catalog = new MockMenuService().loadCatalog().toCompletableFuture().join();

    @Test
    void groupsUseAndWhileCuisinesSpicesAndHighlightsAcceptAnySelectedValue() {
        var filters = new Filters("", Set.of(Cuisine.ETHIOPIAN, Cuisine.SOUTH_SUDANESE),
                Set.of(Diet.VEGAN, Diet.GLUTEN_FREE), Set.of(Allergen.NUTS), Set.of(Spice.MILD, Spice.HOT),
                Set.of(Availability.AVAILABLE), Set.of(Highlight.POPULAR, Highlight.CHEFS_CHOICE), Sort.FEATURED);

        assertEquals(List.of("bean-salad"), ids(catalog, "starters", filters));
    }

    @Test
    void searchesNamesDescriptionsAndIngredientsIgnoringCaseAndAccents() {
        assertEquals(List.of("doro-wat"), ids(catalog, "main-courses", query("DORÓ caramelized egg")));
        assertEquals(List.of("spiced-chai"), ids(catalog, "drinks", query("CARDAMOM")));
        assertTrue(ids(catalog, "main-courses", query("cardamom")).isEmpty());
        assertTrue(ids(catalog, "drinks", query("coffee not-present")).isEmpty());
    }

    @Test
    void excludesDeclaredAllergensCrossContactAndIncompleteData() {
        var unknown = new MenuItem("test", "desserts", Cuisine.INTERNATIONAL,
                new LocalizedText("Test"), new LocalizedText("Gluten-Free"), new LocalizedText("Test"),
                BigDecimal.ONE, null, List.of(), new FoodSafety(Set.of(), Set.of(), Set.of(), false),
                null, Availability.UNKNOWN, Set.of(), null, List.of());
        var extended = new MenuCatalog(catalog.categories(),
                List.of(catalog.item("berry-cheesecake"), catalog.item("tropical-fruit"), unknown), true);

        var excludesNutsAndMilk = new Filters("", Set.of(), Set.of(), Set.of(Allergen.NUTS, Allergen.MILK),
                Set.of(), Set.of(), Set.of(), Sort.FEATURED);
        assertEquals(List.of("tropical-fruit"), ids(extended, "desserts", excludesNutsAndMilk));

        var glutenFree = new Filters("", Set.of(), Set.of(Diet.GLUTEN_FREE), Set.of(),
                Set.of(), Set.of(), Set.of(), Sort.FEATURED);
        assertEquals(List.of("tropical-fruit"), ids(extended, "desserts", glutenFree));

        var crossContactOnly = new Filters("", Set.of(), Set.of(), Set.of(Allergen.SHELLFISH),
                Set.of(), Set.of(), Set.of(), Sort.FEATURED);
        assertEquals(List.of("doro-wat", "kisra-bamia", "beef-tibs", "groundnut-stew", "herb-chicken"),
                ids(catalog, "main-courses", crossContactOnly));
    }

    @Test
    void parentCategoryIncludesDrinkSubcategoriesAndPriceSortUsesNumericValue() {
        assertEquals(6, ids(catalog, "drinks", Filters.defaults()).size());
        assertEquals(List.of("hibiscus-tea", "mango-juice"), ids(catalog, "cold-drinks", Filters.defaults()));

        var sort = new Filters("", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Sort.PRICE_ASC);
        assertEquals(List.of("spiced-chai", "ethiopian-coffee"), ids(catalog, "hot-drinks", sort));

        var soldOut = new Filters("", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(Availability.SOLD_OUT),
                Set.of(), Sort.FEATURED);
        assertEquals(List.of("berry-cheesecake"), ids(catalog, "desserts", soldOut));
        assertTrue(ids(catalog, "drinks", soldOut).isEmpty());
    }

    @Test
    void searchesLocalizedMetadataWithEnglishFallback() {
        var localized = new MenuItem("localized", "desserts", Cuisine.INTERNATIONAL,
                new LocalizedText("Cake", Map.of("ar", "كعكة")), new LocalizedText("Fruit"),
                new LocalizedText("Vanilla"), BigDecimal.ONE, null,
                List.of(new LocalizedText("Pear", Map.of("ar", "كمثرى"))),
                new FoodSafety(Set.of(), Set.of(), Set.of(), true), null, Availability.AVAILABLE, Set.of(), null,
                List.of());
        var translated = new MenuCatalog(catalog.categories(), List.of(localized), false);
        var results = MenuSearch.search(translated, "desserts", query("كعكة pear"), Locale.forLanguageTag("ar"));
        assertEquals(List.of("localized"), results.stream().map(MenuItem::id).toList());
    }

    private static Filters query(String query) {
        return new Filters(query, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Sort.FEATURED);
    }

    private static List<String> ids(MenuCatalog catalog, String category, Filters filters) {
        return MenuSearch.search(catalog, category, filters, Locale.ENGLISH).stream().map(MenuItem::id).toList();
    }
}
