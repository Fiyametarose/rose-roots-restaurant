package com.roseroots.customer.services;

import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.MenuItem.Allergen;
import com.roseroots.customer.models.MenuItem.Availability;
import com.roseroots.customer.models.MenuItem.Cuisine;
import com.roseroots.customer.models.MenuItem.Diet;
import com.roseroots.customer.models.MenuItem.Highlight;
import com.roseroots.customer.models.MenuItem.Spice;
import java.text.Collator;
import java.text.Normalizer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MenuSearch {
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private MenuSearch() {
    }

    public enum Sort { FEATURED, NAME, PRICE_ASC, PRICE_DESC }

    public record Filters(String query, Set<Cuisine> cuisines, Set<Diet> diets,
                          Set<Allergen> excludedAllergens, Set<Spice> spices,
                          Set<Availability> availability, Set<Highlight> highlights, Sort sort) {
        public Filters {
            cuisines = Set.copyOf(cuisines);
            diets = Set.copyOf(diets);
            excludedAllergens = Set.copyOf(excludedAllergens);
            spices = Set.copyOf(spices);
            availability = Set.copyOf(availability);
            highlights = Set.copyOf(highlights);
        }

        public static Filters defaults() {
            return new Filters("", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Sort.FEATURED);
        }
    }

    public static List<MenuItem> search(MenuCatalog catalog, String categoryId, Filters filters, Locale locale) {
        String query = normalize(filters.query()).strip();
        List<String> terms = query.isBlank() ? List.of() : List.of(query.split("(?U)\\s+"));
        var matches = catalog.items().stream()
                .filter(item -> categoryId == null || catalog.belongsTo(item, categoryId))
                .filter(item -> matchesFilters(item, filters))
                .filter(item -> terms.isEmpty() || containsTerms(item, terms, locale));
        return switch (filters.sort()) {
            case FEATURED -> matches.toList();
            case NAME -> matches.sorted(Comparator.comparing(
                    item -> item.name().in(locale), Collator.getInstance(locale))).toList();
            case PRICE_ASC -> matches.sorted(Comparator.comparing(MenuItem::price)).toList();
            case PRICE_DESC -> matches.sorted(Comparator.comparing(MenuItem::price).reversed()).toList();
        };
    }

    private static boolean matchesFilters(MenuItem item, Filters filter) {
        return includes(filter.cuisines(), item.cuisine())
                && item.safety().dietaryLabels().containsAll(filter.diets())
                && excludesAllergens(item, filter.excludedAllergens())
                && includes(filter.spices(), item.spice())
                && includes(filter.availability(), item.availability())
                && (filter.highlights().isEmpty()
                    || !Collections.disjoint(filter.highlights(), item.highlights()));
    }

    private static <T> boolean includes(Set<T> selected, T value) {
        return selected.isEmpty() || (value != null && selected.contains(value));
    }

    private static boolean excludesAllergens(MenuItem item, Set<Allergen> exclusions) {
        var safety = item.safety();
        return exclusions.isEmpty() || (safety.allergenDataComplete()
                && Collections.disjoint(exclusions, safety.contains())
                && Collections.disjoint(exclusions, safety.possibleCrossContact()));
    }

    private static boolean containsTerms(MenuItem item, List<String> terms, Locale locale) {
        var text = Stream.concat(Stream.of(item.name(), item.summary(), item.description()),
                item.ingredients().stream());
        var fields = text.flatMap(value -> Stream.of(value.in(locale), value.english()))
                .map(MenuSearch::normalize).toList();
        return terms.stream().allMatch(term -> fields.stream().anyMatch(field -> field.contains(term)));
    }

    private static String normalize(String text) {
        return DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }
}
