package com.roseroots.customer.services.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.roseroots.customer.models.LocalizedText;
import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.models.MenuCategory;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.services.MenuService;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ConnectedCatalog implements MenuService {
    private final SpringBootOrderService rest;
    private final BackendAddress address;
    private final java.util.concurrent.atomic.AtomicLong refreshGeneration = new java.util.concurrent.atomic.AtomicLong();
    private volatile Map<String, Translation> published = Map.of();

    public ConnectedCatalog(SpringBootOrderService rest, BackendAddress address) {
        this.rest = rest;
        this.address = address;
    }

    @Override public boolean refreshWhileVisible() { return true; }

    @Override public CompletionStage<MenuCatalog> loadCatalog() {
        long generation = refreshGeneration.incrementAndGet();
        var translations = rest.get("menu-translations").thenApply(ConnectedCatalog::translations).handle((next, error) -> {
            if (error != null) return published;
            if (generation == refreshGeneration.get()) published = next;
            return next;
        });
        return rest.get("menu").thenCombine(translations, this::read);
    }

    private MenuCatalog read(JsonObject json, Map<String, Translation> translations) {
        if (!"KES".equals(string(json, "currency"))) throw new IllegalArgumentException("Unexpected catalog currency");
        var categories = list(json, "categories", node -> {
            var category = node.getAsJsonObject();
            String categoryId = id(category, "id");
            return new MenuCategory(categoryId, category.get("parentId").isJsonNull() ? null : id(category, "parentId"),
                    localized(category, "name", "CATEGORY/" + categoryId, translations));
        });
        var items = list(json, "items", node -> item(node.getAsJsonObject(), translations));
        return new MenuCatalog(categories, items, false);
    }

    private MenuItem item(JsonObject item, Map<String, Translation> translations) {
        var dishId = id(item, "id");
        var description = localized(item, "description", "DISH/" + dishId, translations);
        var name = localized(item, "name", "DISH/" + dishId, translations);
        var safety = new MenuItem.FoodSafety(
                enums(item, "dietaryTags", MenuItem.Diet::valueOf),
                enums(item, "allergens", MenuItem.Allergen::valueOf), Set.of(), false);
        var image = item.get("imageReference");
        var prep = item.get("prepMinutes");
        return new MenuItem(dishId, id(item, "categoryId"), MenuItem.Cuisine.valueOf(string(item, "cuisine")),
                name, description, description, item.getAsJsonPrimitive("price").getAsBigDecimal(),
                image == null || image.isJsonNull() ? null : imageUrl(image.getAsString()),
                list(item, "ingredients", ingredient -> new LocalizedText(ingredient.getAsString())),
                safety, null, MenuItem.Availability.valueOf(string(item, "availability")), Set.of(),
                prep == null || prep.isJsonNull() ? null : Duration.ofMinutes(prep.getAsLong()),
                list(item, "customizationGroups", node -> group(dishId, node.getAsJsonObject())));
    }

    private record Translation(Map<String, String> names, Map<String, String> descriptions) {}

    private static Map<String, Translation> translations(JsonObject response) {
        var result = new java.util.HashMap<String, Translation>();
        for (var element : response.getAsJsonArray("translations")) {
            var row = element.getAsJsonObject();
            String locale = string(row, "locale");
            if (!(locale.equals("am") || locale.equals("ar"))) continue;
            String key = string(row, "targetType") + "/" + id(row, "targetId");
            var entry = result.computeIfAbsent(key, ignored -> new Translation(new java.util.HashMap<>(), new java.util.HashMap<>()));
            String name = string(row, "name");
            String description = string(row, "description");
            if (!name.isBlank()) entry.names().put(locale, name);
            if (!description.isBlank()) entry.descriptions().put(locale, description);
        }
        return Map.copyOf(result);
    }

    private static LocalizedText localized(JsonObject row, String field, String key, Map<String, Translation> translations) {
        var entry = translations.get(key);
        return new LocalizedText(string(row, field), entry == null ? Map.of() : field.equals("name") ? entry.names() : entry.descriptions());
    }

    private static MenuItem.CustomizationGroup group(String dishId, JsonObject group) {
        var groupId = orderingOptionId(dishId + "/group/", string(group, "id"));
        return new MenuItem.CustomizationGroup(groupId, new LocalizedText(string(group, "name")),
                MenuItem.CustomizationGroup.Selection.valueOf(string(group, "selection")),
                group.get("minimum").getAsInt(), group.get("maximum").getAsInt(),
                list(group, "choices", node -> {
                    var choice = node.getAsJsonObject();
                    return new MenuItem.CustomizationGroup.Choice(orderingOptionId(groupId + "/option/", string(choice, "id")),
                            new LocalizedText(string(choice, "name")), choice.get("priceAdjustment").getAsBigDecimal(),
                            choice.has("allergens") && !choice.get("allergens").isJsonNull()
                                    ? enums(choice, "allergens", MenuItem.Allergen::valueOf) : Set.of());
                }));
    }

    // Mirrors the integrated server's OrderingPricing.catalogId for legacy catalog references.
    static String orderingOptionId(String namespace, String value) {
        if (value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return value;
        if (value.isBlank()) throw new IllegalArgumentException("Missing customization reference");
        return java.util.UUID.nameUUIDFromBytes((namespace + value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String imageUrl(String location) {
        URI image = URI.create(location);
        if (image.isAbsolute() || image.getRawAuthority() != null || image.getPath() == null
                || !image.normalize().getPath().startsWith("/api/v1/menu-images/")) return null;
        return address.base().resolve(image).toString();
    }

    private static String id(JsonObject object, String key) {
        return Ordering.canonicalId(string(object, key)).toString();
    }

    private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }

    private static <T> List<T> list(JsonObject object, String key, Function<JsonElement, T> mapper) {
        return object.getAsJsonArray(key).asList().stream().map(mapper).toList();
    }

    private static <T> Set<T> enums(JsonObject object, String key, Function<String, T> mapper) {
        return list(object, key, value -> mapper.apply(value.getAsString())).stream().collect(Collectors.toUnmodifiableSet());
    }
}
