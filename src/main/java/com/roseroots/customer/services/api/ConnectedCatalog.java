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
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ConnectedCatalog implements MenuService {
    private final SpringBootOrderService rest;
    private final BackendAddress address;

    public ConnectedCatalog(SpringBootOrderService rest, BackendAddress address) {
        this.rest = rest;
        this.address = address;
    }

    @Override public CompletionStage<MenuCatalog> loadCatalog() {
        return rest.get("menu").thenApply(this::read);
    }

    private MenuCatalog read(JsonObject json) {
        if (!"KES".equals(string(json, "currency"))) throw new IllegalArgumentException("Unexpected catalog currency");
        var categories = list(json, "categories", node -> {
            var category = node.getAsJsonObject();
            return new MenuCategory(id(category, "id"), category.get("parentId").isJsonNull() ? null : id(category, "parentId"),
                    new LocalizedText(string(category, "name")));
        });
        var items = list(json, "items", node -> item(node.getAsJsonObject()));
        return new MenuCatalog(categories, items, false);
    }

    private MenuItem item(JsonObject item) {
        var dishId = id(item, "id");
        var description = new LocalizedText(string(item, "description"));
        var name = new LocalizedText(string(item, "name"));
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

    private static MenuItem.CustomizationGroup group(String dishId, JsonObject group) {
        var groupId = orderingOptionId(dishId + "/group/", string(group, "id"));
        return new MenuItem.CustomizationGroup(groupId, new LocalizedText(string(group, "name")),
                MenuItem.CustomizationGroup.Selection.valueOf(string(group, "selection")),
                group.get("minimum").getAsInt(), group.get("maximum").getAsInt(),
                list(group, "choices", node -> {
                    var choice = node.getAsJsonObject();
                    return new MenuItem.CustomizationGroup.Choice(orderingOptionId(groupId + "/option/", string(choice, "id")),
                            new LocalizedText(string(choice, "name")), choice.get("priceAdjustment").getAsBigDecimal());
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
