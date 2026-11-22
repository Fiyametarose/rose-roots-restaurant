package com.roseroots.server.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public final class CatalogData {
    private CatalogData() {}

    public enum CategoryStatus { VISIBLE, HIDDEN, ARCHIVED }
    public enum Cuisine { ETHIOPIAN, SOUTH_SUDANESE, INTERNATIONAL }
    public enum Availability { AVAILABLE, SOLD_OUT }
    public enum Allergen { NUTS, MILK, EGGS, WHEAT, SOY, FISH, SHELLFISH }
    public enum Diet { VEGETARIAN, VEGAN, GLUTEN_FREE, DAIRY_FREE, FASTING_FRIENDLY }
    public enum Selection { PORTION, SPICE, SIDE, EXTRA }

    public record CategoryInput(UUID parentId, @NotBlank @Size(max = 100) String name,
                                @NotNull @PositiveOrZero Integer displayOrder, @NotNull CategoryStatus status) {}

    public record Category(UUID id, UUID parentId, String name, int displayOrder,
                           CategoryStatus status, long itemCount) {}

    public record Choice(@NotBlank @Size(max = 64) String id, @NotBlank @Size(max = 100) String name,
                         @NotNull @Digits(integer = 10, fraction = 2) BigDecimal priceAdjustment,
                         @Size(max = 20) List<@NotNull Allergen> allergens) {
        public Choice {
            allergens = allergens == null ? List.of() : List.copyOf(allergens);
        }

        public Choice(String id, String name, BigDecimal priceAdjustment) {
            this(id, name, priceAdjustment, List.of());
        }
    }

    public record Customization(@NotBlank @Size(max = 64) String id, @NotBlank @Size(max = 100) String name,
                                @NotNull Selection selection, @NotNull @PositiveOrZero Integer minimum,
                                @NotNull @Positive Integer maximum,
                                @NotEmpty @Size(max = 50) List<@NotNull @Valid Choice> choices) {}

    public record DishInput(@NotNull UUID categoryId, @NotBlank @Size(max = 120) String name,
                            @NotBlank @Size(max = 4000) String description, @NotNull Cuisine cuisine,
                            @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal price,
                            @Size(max = 100) String servingSize,
                            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> ingredients,
                            @NotNull @Size(max = 20) List<@NotNull Allergen> allergens,
                            @NotNull @Size(max = 20) List<@NotNull Diet> dietaryTags,
                            @Min(1) @Max(1440) Integer prepMinutes,
                            @Size(max = 80) String kitchenStation, @NotNull Availability availability,
                            @NotNull @Size(max = 30) List<@NotNull @Valid Customization> customizationGroups,
                            @Size(max = 1024) String imageReference, @NotNull @PositiveOrZero Integer displayOrder,
                            @Positive Long expectedVersion, @Valid PreparationInput preparation) {
        public DishInput(UUID categoryId, String name, String description, Cuisine cuisine, BigDecimal price,
                         String servingSize, List<String> ingredients, List<Allergen> allergens, List<Diet> dietaryTags,
                         Integer prepMinutes, String kitchenStation, Availability availability,
                         List<Customization> customizationGroups, String imageReference, Integer displayOrder) {
            this(categoryId, name, description, cuisine, price, servingSize, ingredients, allergens, dietaryTags,
                    prepMinutes, kitchenStation, availability, customizationGroups, imageReference, displayOrder, null, null);
        }
    }

    public record PreparationInput(@NotNull UUID stationId, @NotNull @Min(1) @Max(86400) Integer durationSeconds,
                                   @Min(1) @Max(86400) Integer upperDurationSeconds,
                                   @NotNull @Size(max=900) List<@NotNull @Valid OptionWorkInput> options) {}
    public record OptionWorkInput(@NotBlank String groupId, @NotBlank String optionId,
                                  @NotNull @Min(0) @Max(86400) Integer lowerSeconds,
                                  @NotNull @Min(0) @Max(86400) Integer upperSeconds) {}
    public record OptionWork(UUID groupId, UUID optionId, int lowerSeconds, int upperSeconds) {}
    public record Preparation(UUID stationId, String stationName, int durationSeconds, Integer upperDurationSeconds,
                              long version, List<OptionWork> options) {}

    public record Dish(UUID id, UUID categoryId, String name, String description, Cuisine cuisine,
                       BigDecimal price, String servingSize, List<String> ingredients, List<Allergen> allergens,
                       List<Diet> dietaryTags, Integer prepMinutes, String kitchenStation, Availability availability,
                       boolean archived, List<Customization> customizationGroups, String imageReference,
                       int displayOrder, String customerSourceId, SourceDetails sourceDetails, long version,
                       Preparation preparation) {}

    public static UUID orderingId(String value, String namespace) {
        if (value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return UUID.fromString(value);
        return UUID.nameUUIDFromBytes((namespace + value).getBytes(StandardCharsets.UTF_8));
    }

    /** Customer development metadata retained for review. It is not a verified safety declaration. */
    public record SourceDetails(String summary, String spice, List<String> highlights,
                                List<Allergen> reportedAllergens, List<Allergen> reportedCrossContact,
                                List<Diet> reportedDietaryTags, boolean sourceAllergenDataComplete,
                                Map<String, List<Allergen>> reportedChoiceAllergens) {}

    public record TradingUpdate(@PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal price,
                                Availability availability, Boolean archived, @Size(max = 500) String reason) {}

    public record Snapshot(String currency, List<Category> categories, List<Dish> items) {}
}
