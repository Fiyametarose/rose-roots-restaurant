package com.roseroots.server.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import static com.roseroots.server.catalog.CatalogData.*;

@Entity
@Table(name = "catalog_item")
class DishEntity {
    @Id UUID id;
    UUID categoryId;
    String name;
    String description;
    @Enumerated(EnumType.STRING) Cuisine cuisine;
    BigDecimal price;
    String servingSize;
    @JdbcTypeCode(SqlTypes.JSON) List<String> ingredients;
    @JdbcTypeCode(SqlTypes.JSON) List<Allergen> allergens;
    @JdbcTypeCode(SqlTypes.JSON) List<Diet> dietaryTags;
    Integer prepMinutes;
    String kitchenStation;
    @Enumerated(EnumType.STRING) Availability availability;
    boolean archived;
    @JdbcTypeCode(SqlTypes.JSON) List<Customization> customizationGroups;
    String imageReference;
    int displayOrder;
    String customerSourceId;
    @JdbcTypeCode(SqlTypes.JSON) SourceDetails sourceDetails;

    protected DishEntity() {}

    void apply(DishInput input) {
        categoryId = input.categoryId();
        name = input.name().strip();
        description = input.description().strip();
        cuisine = input.cuisine();
        price = input.price().setScale(2);
        servingSize = optional(input.servingSize());
        ingredients = input.ingredients().stream().map(String::strip).distinct().toList();
        allergens = input.allergens().stream().distinct().toList();
        dietaryTags = input.dietaryTags().stream().distinct().toList();
        prepMinutes = input.prepMinutes();
        kitchenStation = optional(input.kitchenStation());
        availability = input.availability();
        customizationGroups = List.copyOf(input.customizationGroups());
        imageReference = input.imageReference() == null || input.imageReference().isBlank()
                ? null : input.imageReference().strip();
        displayOrder = input.displayOrder();
    }

    Dish toView() {
        return new Dish(id, categoryId, name, description, cuisine, price, servingSize, ingredients, allergens,
                dietaryTags, prepMinutes, kitchenStation, availability, archived, customizationGroups,
                imageReference, displayOrder, customerSourceId, sourceDetails);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
