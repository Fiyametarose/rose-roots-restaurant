package com.roseroots.customer.models;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public record MenuItem(String id, String categoryId, Cuisine cuisine, LocalizedText name,
                       LocalizedText summary, LocalizedText description, BigDecimal price,
                       String image, List<LocalizedText> ingredients, FoodSafety safety,
                       Spice spice, Availability availability, Set<Highlight> highlights,
                       Duration preparationTime, List<CustomizationGroup> customizationGroups) {
    public MenuItem {
        ingredients = List.copyOf(ingredients);
        highlights = Set.copyOf(highlights);
        customizationGroups = List.copyOf(customizationGroups);
    }

    public enum Cuisine { ETHIOPIAN, SOUTH_SUDANESE, INTERNATIONAL }
    public enum Diet { VEGETARIAN, VEGAN, GLUTEN_FREE, DAIRY_FREE, FASTING_FRIENDLY }
    public enum Allergen { NUTS, MILK, EGGS, WHEAT, SOY, FISH, SHELLFISH }
    public enum Spice { MILD, MEDIUM, HOT }
    public enum Availability { AVAILABLE, SOLD_OUT, UNKNOWN }
    public enum Highlight { POPULAR, CHEFS_CHOICE }

    /** Production providers supply only verified dietary labels and declared allergen metadata. */
    public record FoodSafety(Set<Diet> dietaryLabels, Set<Allergen> contains,
                             Set<Allergen> possibleCrossContact, boolean allergenDataComplete) {
        public FoodSafety {
            dietaryLabels = Set.copyOf(dietaryLabels);
            contains = Set.copyOf(contains);
            possibleCrossContact = Set.copyOf(possibleCrossContact);
        }
    }

    public record CustomizationGroup(String id, LocalizedText name, Selection selection,
                                     int minimum, int maximum, List<Choice> choices) {
        public CustomizationGroup {
            choices = List.copyOf(choices);
        }

        public enum Selection { PORTION, SPICE, SIDE, EXTRA }

        public record Choice(String id, LocalizedText name, BigDecimal priceAdjustment) {
        }
    }
}
