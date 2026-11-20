package com.roseroots.customer.models;

import com.roseroots.customer.models.MenuItem.Allergen;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ConfiguredDish(MenuItem item, int quantity, Map<String, Set<String>> selections,
                             String instructions, AllergyDeclaration allergy) {
    public static final int MAX_QUANTITY = 99;

    public ConfiguredDish {
        selections = selections.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        instructions = instructions.strip();
        if (quantity < 1 || quantity > MAX_QUANTITY || instructions.length() > 240) {
            throw new IllegalArgumentException("Invalid quantity or instructions");
        }
        var known = item.customizationGroups().stream().map(MenuItem.CustomizationGroup::id).collect(Collectors.toSet());
        if (!known.containsAll(selections.keySet())) {
            throw new IllegalArgumentException("Unknown customization group");
        }
        for (var group : item.customizationGroups()) {
            var chosen = selections.getOrDefault(group.id(), Set.of());
            if (chosen.size() < group.minimum() || chosen.size() > group.maximum()
                    || chosen.stream().anyMatch(id -> group.choices().stream().noneMatch(choice -> choice.id().equals(id)))) {
                throw new IllegalArgumentException("Invalid choices for " + group.id());
            }
        }
    }

    public static Map<String, Set<String>> defaults(MenuItem item) {
        return item.customizationGroups().stream().collect(Collectors.toMap(MenuItem.CustomizationGroup::id,
                group -> group.choices().stream().limit(group.minimum()).map(MenuItem.CustomizationGroup.Choice::id)
                        .collect(Collectors.toSet())));
    }

    public List<MenuItem.CustomizationGroup.Choice> choices() {
        return item.customizationGroups().stream().flatMap(group -> group.choices().stream()
                .filter(choice -> selections.getOrDefault(group.id(), Set.of()).contains(choice.id()))).toList();
    }

    public BigDecimal total() {
        return choices().stream().map(MenuItem.CustomizationGroup.Choice::priceAdjustment)
                .reduce(item.price(), BigDecimal::add).multiply(BigDecimal.valueOf(quantity));
    }

    public ConfiguredDish withQuantity(int next) { return new ConfiguredDish(item, next, selections, instructions, allergy); }

    public record Review(Set<Allergen> ingredients, Set<Allergen> crossContact, boolean uncertain) {
        public boolean requiresReview() { return uncertain || !ingredients.isEmpty() || !crossContact.isEmpty(); }
    }

    public Review review(AllergyDeclaration declaration) {
        var disclosed = declaration.allergens().stream().map(value -> switch (value) {
            case PEANUTS, TREE_NUTS -> Allergen.NUTS;
            default -> Allergen.valueOf(value.name());
        }).collect(Collectors.toSet());
        var ingredients = EnumSet.noneOf(Allergen.class);
        ingredients.addAll(item.safety().contains());
        choices().forEach(choice -> ingredients.addAll(choice.contains()));
        ingredients.retainAll(disclosed);
        var cross = item.safety().possibleCrossContact().stream().filter(disclosed::contains).collect(Collectors.toSet());
        return new Review(Set.copyOf(ingredients), cross, declaration.disclosed()
                && (!declaration.other().isEmpty() || !item.safety().allergenDataComplete()));
    }
}
