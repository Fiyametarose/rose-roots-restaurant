package com.roseroots.customer.services.mock;

import com.roseroots.customer.models.LocalizedText;
import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.models.MenuCategory;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.MenuItem.Allergen;
import com.roseroots.customer.models.MenuItem.Availability;
import com.roseroots.customer.models.MenuItem.Cuisine;
import com.roseroots.customer.models.MenuItem.Diet;
import com.roseroots.customer.models.MenuItem.FoodSafety;
import com.roseroots.customer.models.MenuItem.Highlight;
import com.roseroots.customer.models.MenuItem.Spice;
import com.roseroots.customer.services.MenuService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Sample metadata and prices for exercising the customer UI locally. */
public final class MockMenuService implements MenuService {
    private static final MenuCatalog CATALOG = new MenuCatalog(List.of(
            category("starters", null, "Starters", "مقبلات", "መክሰስ"),
            category("main-courses", null, "Main Courses", "الأطباق الرئيسية", "ዋና ምግቦች"),
            category("desserts", null, "Desserts", "حلويات", "ጣፋጭ ምግቦች"),
            category("drinks", null, "Drinks", "مشروبات", "መጠጦች"),
            category("hot-drinks", "drinks", "Hot Drinks", "مشروبات ساخنة", "ትኩስ መጠጦች"),
            category("cold-drinks", "drinks", "Cold Drinks", "مشروبات باردة", "ቀዝቃዛ መጠጦች"),
            category("shakes", "drinks", "Shakes", "ميلك شيك", "ሚልክሼክ")),
            List.of(
                    item("doro-wat", "main-courses", Cuisine.ETHIOPIAN, "Doro Wat",
                            "Slow-cooked chicken and egg in a rich berbere sauce.",
                            "Chicken and hard-boiled egg simmered with caramelized onions and berbere. Served with injera for scooping up the sauce.",
                            1450, List.of("Chicken", "Egg", "Red onion", "Berbere", "Injera"),
                            new FoodSafety(Set.of(),
                                    Set.of(Allergen.EGGS), Set.of(Allergen.WHEAT), true),
                            Spice.MEDIUM, Availability.AVAILABLE, Set.of(Highlight.POPULAR), 35),
                    item("kisra-bamia", "main-courses", Cuisine.SOUTH_SUDANESE, "Kisra with Bamia",
                            "Tender okra in a tomato and onion sauce, served with kisra.",
                            "Okra is simmered with tomatoes, onions, and garlic. Thin fermented sorghum flatbread is served on the side.",
                            1250, List.of("Okra", "Tomato", "Onion", "Garlic", "Sorghum"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.DAIRY_FREE, Diet.FASTING_FRIENDLY),
                                    Set.of(), Set.of(Allergen.WHEAT), true),
                            Spice.MILD, Availability.AVAILABLE, Set.of(), 25),
                    item("grilled-fish", "main-courses", Cuisine.INTERNATIONAL, "Grilled Fish",
                            "Herb-grilled fish with lemon, potatoes, and seasonal vegetables.",
                            "A whole grilled tilapia finished with lemon and fresh herbs, served with roast potatoes and vegetables.",
                            1750, List.of("Tilapia", "Lemon", "Parsley", "Potato", "Seasonal vegetables", "Olive oil"),
                            new FoodSafety(Set.of(Diet.GLUTEN_FREE, Diet.DAIRY_FREE),
                                    Set.of(Allergen.FISH), Set.of(Allergen.SHELLFISH), true),
                            Spice.MILD, Availability.AVAILABLE, Set.of(Highlight.CHEFS_CHOICE), 24),
                    item("lentil-sambusa", "starters", Cuisine.ETHIOPIAN, "Lentil Sambusa",
                            "Crisp triangular pastry filled with spiced lentils.",
                            "A crisp folded pastry with lentils, onion and chili, served with a coriander and lemon dip.",
                            490, List.of("Lentils", "Wheat pastry", "Onion", "Chili", "Coriander", "Lemon"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.DAIRY_FREE, Diet.FASTING_FRIENDLY),
                                    Set.of(Allergen.WHEAT), Set.of(Allergen.SOY), true),
                            Spice.HOT, Availability.AVAILABLE, Set.of(Highlight.POPULAR), 12),
                    item("bean-salad", "starters", Cuisine.SOUTH_SUDANESE, "Black-Eyed Pea Salad",
                            "Black-eyed peas tossed with tomatoes, herbs, and lemon.",
                            "Cooked black-eyed peas chilled with tomato, chopped herbs and a bright lemon dressing.",
                            520, List.of("Black-eyed peas", "Tomato", "Parsley", "Lemon", "Olive oil"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.GLUTEN_FREE, Diet.DAIRY_FREE, Diet.FASTING_FRIENDLY),
                                    Set.of(), Set.of(), true),
                            Spice.MILD, Availability.AVAILABLE, Set.of(Highlight.CHEFS_CHOICE), 8),
                    item("berry-cheesecake", "desserts", Cuisine.INTERNATIONAL, "Berry Cheesecake",
                            "Vanilla cheesecake with fresh raspberries.",
                            "A slice of baked vanilla cheesecake on a biscuit crust, finished with fresh raspberries.",
                            620, List.of("Cream cheese", "Wheat biscuit", "Egg", "Vanilla", "Raspberry"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN),
                                    Set.of(Allergen.WHEAT, Allergen.MILK, Allergen.EGGS), Set.of(Allergen.NUTS), true),
                            null, Availability.SOLD_OUT, Set.of(Highlight.POPULAR), null),
                    item("tropical-fruit", "desserts", Cuisine.SOUTH_SUDANESE, "Tropical Fruit Plate",
                            "Sliced mango, pineapple, and orange.",
                            "A chilled plate of ripe mango, pineapple and orange slices.",
                            460, List.of("Mango", "Pineapple", "Orange"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.GLUTEN_FREE, Diet.DAIRY_FREE, Diet.FASTING_FRIENDLY),
                                    Set.of(), Set.of(), true),
                            null, Availability.AVAILABLE, Set.of(), 6),
                    item("ethiopian-coffee", "hot-drinks", Cuisine.ETHIOPIAN, "Ethiopian Coffee",
                            "Aromatic brewed coffee with a bold finish.",
                            "Freshly brewed Ethiopian coffee served black.",
                            350, List.of("Coffee", "Water"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.GLUTEN_FREE, Diet.DAIRY_FREE, Diet.FASTING_FRIENDLY),
                                    Set.of(), Set.of(), true),
                            null, Availability.AVAILABLE, Set.of(Highlight.POPULAR), 8),
                    item("spiced-chai", "hot-drinks", Cuisine.INTERNATIONAL, "Spiced Chai",
                            "A warming blend of tea, milk and whole spices.",
                            "Black tea simmered with milk, cinnamon, cardamom and clove.",
                            300, List.of("Black tea", "Milk", "Cinnamon", "Cardamom", "Clove"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.GLUTEN_FREE),
                                    Set.of(Allergen.MILK), Set.of(), true),
                            null, Availability.AVAILABLE, Set.of(), 9),
                    item("hibiscus-tea", "cold-drinks", Cuisine.SOUTH_SUDANESE, "Hibiscus Iced Tea",
                            "Chilled hibiscus tea with a tangy floral finish.",
                            "Hibiscus petals steeped and chilled, served with ice and a fresh mint garnish.",
                            450, List.of("Hibiscus", "Water", "Mint", "Sugar"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.GLUTEN_FREE, Diet.DAIRY_FREE, Diet.FASTING_FRIENDLY),
                                    Set.of(), Set.of(), true),
                            null, Availability.AVAILABLE, Set.of(), 5),
                    item("mango-juice", "cold-drinks", Cuisine.INTERNATIONAL, "Fresh Mango Juice",
                            "A refreshing juice blended from ripe mangoes.",
                            "Ripe mangoes blended with chilled water for a naturally sweet juice.",
                            550, List.of("Mango", "Water"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.GLUTEN_FREE, Diet.DAIRY_FREE, Diet.FASTING_FRIENDLY),
                                    Set.of(), Set.of(), true),
                            null, Availability.AVAILABLE, Set.of(Highlight.POPULAR), 6),
                    item("avocado-shake", "shakes", Cuisine.ETHIOPIAN, "Avocado Shake",
                            "A creamy shake with ripe avocado and milk.",
                            "Ripe avocado blended with chilled milk and vanilla.",
                            750, List.of("Avocado", "Milk", "Vanilla"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.GLUTEN_FREE),
                                    Set.of(Allergen.MILK), Set.of(Allergen.NUTS), true),
                            null, Availability.AVAILABLE, Set.of(Highlight.CHEFS_CHOICE), 8),
                    item("chocolate-shake", "shakes", Cuisine.INTERNATIONAL, "Chocolate Shake",
                            "A cold cocoa shake with cream.",
                            "Chocolate ice cream blended with milk and finished with whipped cream.",
                            700, List.of("Chocolate ice cream", "Milk", "Cream", "Cocoa"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN),
                                    Set.of(Allergen.MILK, Allergen.SOY), Set.of(Allergen.NUTS), true),
                            null, Availability.AVAILABLE, Set.of(Highlight.POPULAR), 8),
                    item("beef-tibs", "main-courses", Cuisine.ETHIOPIAN, "Beef Tibs",
                            "Seared beef with peppers and onions.",
                            "Tender beef pieces seared with peppers, onions and rosemary.",
                            1560, List.of("Beef", "Pepper", "Onion", "Rosemary"),
                            new FoodSafety(Set.of(Diet.DAIRY_FREE), Set.of(), Set.of(), true),
                            Spice.HOT, Availability.AVAILABLE, Set.of(Highlight.POPULAR), 22),
                    item("groundnut-stew", "main-courses", Cuisine.SOUTH_SUDANESE, "Groundnut Stew",
                            "Creamy groundnut and vegetable stew with rice.",
                            "Sweet potato and leafy greens simmered in a groundnut sauce, served with rice.",
                            1190, List.of("Groundnuts", "Sweet potato", "Greens", "Rice"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.DAIRY_FREE),
                                    Set.of(Allergen.NUTS), Set.of(), true),
                            Spice.MILD, Availability.AVAILABLE, Set.of(), 27),
                    item("herb-chicken", "main-courses", Cuisine.INTERNATIONAL, "Herb Chicken",
                            "Roasted chicken with herbs and seasonal vegetables.",
                            "Herb-marinated roast chicken with roasted peppers, carrots and potatoes.",
                            1390, List.of("Chicken", "Rosemary", "Carrot", "Potato", "Pepper"),
                            new FoodSafety(Set.of(Diet.DAIRY_FREE), Set.of(), Set.of(), true),
                            Spice.MILD, Availability.AVAILABLE, Set.of(), 29),
                    item("azifa", "starters", Cuisine.ETHIOPIAN, "Azifa Lentil Salad",
                            "Chilled green lentils with onion and lemon.",
                            "Green lentils, onion, fresh herbs and lemon dressing served chilled.",
                            480, List.of("Lentils", "Onion", "Parsley", "Lemon"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.DAIRY_FREE),
                                    Set.of(), Set.of(), true),
                            Spice.MILD, Availability.AVAILABLE, Set.of(), 9),
                    item("bruschetta", "starters", Cuisine.INTERNATIONAL, "Tomato Bruschetta",
                            "Toasted bread with ripe tomato and basil.",
                            "Grilled wheat bread topped with tomatoes, basil, garlic and olive oil.",
                            560, List.of("Bread", "Tomato", "Basil", "Garlic", "Olive oil"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN, Diet.VEGAN, Diet.DAIRY_FREE),
                                    Set.of(Allergen.WHEAT), Set.of(), true),
                            null, Availability.AVAILABLE, Set.of(), 8),
                    item("chocolate-mousse", "desserts", Cuisine.INTERNATIONAL, "Chocolate Mousse",
                            "Dark chocolate mousse with fresh raspberries.",
                            "Silky dark chocolate mousse topped with raspberries and shaved chocolate.",
                            590, List.of("Dark chocolate", "Cream", "Egg", "Raspberry"),
                            new FoodSafety(Set.of(Diet.VEGETARIAN),
                                    Set.of(Allergen.MILK, Allergen.EGGS), Set.of(Allergen.NUTS), true),
                            null, Availability.AVAILABLE, Set.of(Highlight.CHEFS_CHOICE), null)), true);

    private final MenuCatalog visibleCatalog;

    public MockMenuService() {
        this(true);
    }

    public MockMenuService(boolean developmentMode) {
        visibleCatalog = developmentMode ? CATALOG : new MenuCatalog(CATALOG.categories(),
                CATALOG.items().stream().map(MockMenuService::previewItem).toList(), true);
    }

    private static MenuItem previewItem(MenuItem item) {
        return new MenuItem(item.id(), item.categoryId(), item.cuisine(), item.name(), item.summary(),
                item.description(), item.price(), item.image(), item.ingredients(),
                new FoodSafety(Set.of(), Set.of(), Set.of(), false), item.spice(), item.availability(),
                item.highlights(), null, item.customizationGroups());
    }

    @Override
    public CompletionStage<MenuCatalog> loadCatalog() {
        return CompletableFuture.completedFuture(visibleCatalog);
    }

    private static MenuCategory category(String id, String parent, String name, String arabic, String amharic) {
        return new MenuCategory(id, parent, new LocalizedText(name, Map.of("ar", arabic, "am", amharic)));
    }

    private static MenuItem item(String id, String category, Cuisine cuisine, String name,
                                 String summary, String description, int price, List<String> ingredients,
                                 FoodSafety safety, Spice spice, Availability availability,
                                 Set<Highlight> highlights, Integer minutes) {
        return new MenuItem(id, category, cuisine, new LocalizedText(name), new LocalizedText(summary),
                new LocalizedText(description), BigDecimal.valueOf(price), "/images/menu/" + id + ".jpg",
                ingredients.stream().map(LocalizedText::new).toList(), safety, spice, availability, highlights,
                minutes == null ? null : Duration.ofMinutes(minutes), List.of());
    }
}
