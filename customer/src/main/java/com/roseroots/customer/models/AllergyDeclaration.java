package com.roseroots.customer.models;

import java.util.Set;

public record AllergyDeclaration(boolean none, Set<Allergen> allergens, String other) {
    public AllergyDeclaration {
        allergens = Set.copyOf(allergens);
        other = other.strip();
        if (other.length() > 240 || (none && (!allergens.isEmpty() || !other.isEmpty()))) {
            throw new IllegalArgumentException("Invalid allergy declaration");
        }
    }

    public static AllergyDeclaration unreviewed() { return new AllergyDeclaration(false, Set.of(), ""); }
    public boolean disclosed() { return !allergens.isEmpty() || !other.isEmpty(); }
    public enum Allergen { MILK, EGGS, WHEAT, PEANUTS, TREE_NUTS, SOY, FISH, SHELLFISH }
}
