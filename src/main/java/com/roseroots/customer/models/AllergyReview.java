package com.roseroots.customer.models;

/** Payload for a future order line. Only an authorized server response may approve preparation. */
public record AllergyReview(AllergyDeclaration itemDeclaration, AllergyDeclaration visitDeclaration,
                            String preparationInstructions, ConfiguredDish.Review itemRisks,
                            ConfiguredDish.Review visitRisks, Status status) {
    public enum Status {
        NOT_REQUIRED, PENDING_ALLERGY_REVIEW, CLARIFICATION_REQUESTED, DEFERRED,
        CUSTOMER_APPROVAL_REQUIRED, APPROVED_FOR_PREPARATION, DECLINED
    }

    public java.util.Set<MenuItem.Allergen> ingredientConflicts() {
        var matches = java.util.EnumSet.noneOf(MenuItem.Allergen.class);
        matches.addAll(itemRisks.ingredients());
        matches.addAll(visitRisks.ingredients());
        return java.util.Set.copyOf(matches);
    }

    public static AllergyReview forDish(ConfiguredDish dish, AllergyDeclaration visit) {
        var item = dish.allergy();
        var itemRisks = dish.review(item);
        var visitRisks = dish.review(visit);
        var initial = item.disclosed() || visit.disclosed() || itemRisks.requiresReview() || visitRisks.requiresReview()
                ? Status.PENDING_ALLERGY_REVIEW : Status.NOT_REQUIRED;
        return new AllergyReview(item, visit, dish.instructions(), itemRisks, visitRisks, initial);
    }
}
