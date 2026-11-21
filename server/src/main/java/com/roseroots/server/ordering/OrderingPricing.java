package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roseroots.server.catalog.CatalogData.Allergen;
import com.roseroots.server.catalog.CatalogData.Dish;
import com.roseroots.server.catalog.CatalogService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static com.roseroots.server.ordering.OrderingJson.*;

@Component
@Profile("catalog")
class OrderingPricing {
    private static final Set<String> ALLERGENS = Set.of("MILK", "EGGS", "WHEAT", "PEANUTS",
            "TREE_NUTS", "SOY", "FISH", "SHELLFISH", "NUTS_UNSPECIFIED");

    record Line(UUID menuItemId, ObjectNode pricing, JsonNode allergy, String instructions,
                ObjectNode risk, int prepMinutes) {}

    record Price(List<Line> lines, long subtotal) {
        ObjectNode snapshot() {
            ArrayNode pricedLines = array();
            lines.forEach(line -> pricedLines.add(line.pricing()));
            ObjectNode result = object();
            result.set("lines", pricedLines);
            result.set("totals", totals(subtotal));
            return result;
        }
    }

    private final CatalogService catalog;
    private final String currency;

    OrderingPricing(CatalogService catalog, @Value("${rose.catalog.currency}") String currency) {
        this.catalog = catalog;
        this.currency = currency;
    }

    static ObjectNode totals(long subtotal) {
        return object().put("subtotalMinor", subtotal).put("serviceMinor", 0).put("taxMinor", 0)
                .put("totalMinor", subtotal).put("currency", "KES");
    }

    static ObjectNode draft(JsonNode body) {
        ObjectNode draft = object();
        draft.put("sessionId", id(body, "sessionId").toString());
        draft.set("visitAllergy", body.get("visitAllergy"));
        draft.set("lines", list(body, "lines", 1, 100));
        return draft;
    }

    Price price(JsonNode draft) {
        if (!currency.equalsIgnoreCase("KES")) {
            throw new OrderingProblem(503, "SERVICE_UNAVAILABLE", "The catalog currency is unavailable for v1 ordering.");
        }
        JsonNode visit = declaration(draft.get("visitAllergy"));
        var seen = new HashSet<UUID>();
        var dishes = new HashMap<UUID, Dish>();
        var lines = new ArrayList<Line>();
        long subtotal = 0;
        for (JsonNode line : draft.get("lines")) {
            fields(line, "clientLineId", "menuItemId", "quantity", "selections", "allergy", "preparationInstructions");
            UUID clientLineId = id(line, "clientLineId");
            if (!seen.add(clientLineId)) {
                throw invalidSelection("Client line IDs must be unique.");
            }
            UUID itemId = id(line, "menuItemId");
            Dish dish = dishes.computeIfAbsent(itemId, this::requireDish);
            int quantity = (int) integer(line, "quantity", 1, 99);
            JsonNode allergy = declaration(line.get("allergy"));
            String instructions = text(line, "preparationInstructions", 240);
            ArrayNode options = options(dish, list(line, "selections", 0, 30));
            long baseUnit = minor(dish.price());
            long unit = baseUnit;
            for (JsonNode option : options) {
                unit += option.path("unitAdjustmentMinor").longValue();
            }
            long extended = amount(unit * quantity);
            subtotal = amount(subtotal + extended);
            ObjectNode pricing = object().put("clientLineId", clientLineId.toString()).put("menuItemId", itemId.toString())
                    .put("name", dish.name()).put("quantity", quantity).put("baseUnitMinor", baseUnit)
                    .put("extendedMinor", extended);
            pricing.set("options", options);
            lines.add(new Line(itemId, pricing, allergy, instructions, risk(dish, visit, allergy), dish.prepMinutes()));
        }
        return new Price(List.copyOf(lines), subtotal);
    }

    private Dish requireDish(UUID id) {
        try {
            return catalog.requireOrderable(id);
        } catch (ResponseStatusException e) {
            throw new OrderingProblem(422, "ITEM_UNAVAILABLE", "A selected dish is unavailable.");
        }
    }

    private static ArrayNode options(Dish dish, JsonNode requested) {
        var choicesByGroup = new HashMap<UUID, Set<UUID>>();
        for (JsonNode selection : requested) {
            fields(selection, "groupId", "optionIds");
            var choices = new HashSet<UUID>();
            for (JsonNode option : list(selection, "optionIds", 1, 30)) {
                if (!option.isTextual() || !choices.add(id(option.textValue()))) {
                    throw invalidSelection("Choose each option at most once.");
                }
            }
            if (choicesByGroup.putIfAbsent(id(selection, "groupId"), choices) != null) {
                throw invalidSelection("Choose each customization group at most once.");
            }
        }
        ArrayNode result = array();
        for (var group : dish.customizationGroups()) {
            UUID groupId = catalogId(group.id(), dish.id() + "/group/");
            Set<UUID> selected = choicesByGroup.getOrDefault(groupId, Set.of());
            if (selected.size() < group.minimum() || selected.size() > group.maximum()) {
                throw invalidSelection("A customization group does not meet its selection limits.");
            }
            choicesByGroup.remove(groupId);
            int matched = 0;
            for (var choice : group.choices()) {
                UUID optionId = catalogId(choice.id(), groupId + "/option/");
                if (selected.contains(optionId)) {
                    result.add(object().put("groupId", groupId.toString()).put("optionId", optionId.toString())
                            .put("name", choice.name()).put("unitAdjustmentMinor", minor(choice.priceAdjustment())));
                    matched++;
                }
            }
            if (matched != selected.size()) {
                throw invalidSelection("An option does not belong to the selected customization group.");
            }
        }
        if (!choicesByGroup.isEmpty() || result.size() > 100) {
            throw invalidSelection("The selected customization groups are invalid.");
        }
        return result;
    }

    // Catalog IDs that already satisfy the v1 contract keep their existing identity.
    private static UUID catalogId(String value, String namespace) {
        if (value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            return UUID.fromString(value);
        }
        return UUID.nameUUIDFromBytes((namespace + value).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode declaration(JsonNode node) {
        fields(node, "kind", "allergens", "other");
        String kind = choice(node, "kind", "UNREVIEWED", "NONE", "DISCLOSED");
        var seen = new HashSet<String>();
        JsonNode allergens = list(node, "allergens", 0, 9);
        String other = text(node, "other", 240);
        for (JsonNode tag : allergens) {
            if (!ALLERGENS.contains(tag.asText()) || !seen.add(tag.asText())) {
                throw OrderingProblem.bad("Invalid allergen declaration.");
            }
        }
        if (kind.equals("DISCLOSED") ? seen.isEmpty() && other.isBlank() : !seen.isEmpty() || !other.isEmpty()) {
            throw OrderingProblem.bad("The allergy declaration is inconsistent.");
        }
        return node;
    }

    private static ObjectNode risk(Dish dish, JsonNode visit, JsonNode line) {
        var disclosed = new LinkedHashSet<String>();
        visit.get("allergens").forEach(tag -> disclosed.add(tag.asText()));
        line.get("allergens").forEach(tag -> disclosed.add(tag.asText()));
        ObjectNode risk = object();
        risk.set("ingredientMatches", matches(disclosed, dish.allergens()));
        risk.set("crossContactMatches", matches(disclosed,
                dish.sourceDetails() == null ? List.of() : dish.sourceDetails().reportedCrossContact()));
        // The current catalog does not verify cross-contact and choice safety for every dish.
        return risk.put("unresolved", visit.path("kind").asText().equals("DISCLOSED")
                || line.path("kind").asText().equals("DISCLOSED"));
    }

    private static ArrayNode matches(Set<String> disclosed, List<Allergen> reported) {
        ArrayNode matches = array();
        for (String tag : disclosed) {
            boolean nut = Set.of("NUTS_UNSPECIFIED", "PEANUTS", "TREE_NUTS").contains(tag);
            if (reported != null && reported.stream().anyMatch(allergen -> allergen.name().equals(tag)
                    || allergen == Allergen.NUTS && nut)) {
                matches.add(tag);
            }
        }
        return matches;
    }

    private static OrderingProblem invalidSelection(String detail) {
        return new OrderingProblem(422, "INVALID_SELECTION", detail);
    }

    private static long minor(BigDecimal amount) { return amount.movePointRight(2).longValueExact(); }

    static long amount(long minor) {
        if (minor < 0 || minor > MAX_SAFE) {
            throw invalidSelection("The calculated amount is outside the supported range.");
        }
        return minor;
    }
}
