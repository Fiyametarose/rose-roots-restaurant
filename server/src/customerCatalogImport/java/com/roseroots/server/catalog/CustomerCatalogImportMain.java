package com.roseroots.server.catalog;

import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.server.ServerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Properties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static com.roseroots.server.catalog.CatalogData.*;
import static com.roseroots.server.catalog.CatalogImportService.*;

public final class CustomerCatalogImportMain {
    private CustomerCatalogImportMain() {}

    public static void main(String[] args) throws Exception {
        Path resources = Path.of(args[0]).resolve("src/main/resources").toRealPath();
        String mode = args[1];
        if (!List.of("inventory", "dry-run", "apply").contains(mode)) {
            throw new IllegalArgumentException("catalogMode must be inventory, dry-run or apply.");
        }
        var source = new MockMenuService(true).loadCatalog().toCompletableFuture().join();
        var configuration = new Properties();
        try (var reader = Files.newBufferedReader(resources.resolve("restaurant.properties"))) {
            configuration.load(reader);
        }
        String currency = configuration.getProperty("currency");
        var sections = new ArrayList<Section>();
        for (var category : source.categories()) {
            sections.add(new Section(category.id(), category.parentId(), category.name().english(),
                    10 * (1 + (int) sections.stream().filter(section ->
                            java.util.Objects.equals(section.parentId(), category.parentId())).count())));
        }
        var items = new ArrayList<Item>();
        for (var item : source.items()) {
            items.add(map(item, resources, 10 * (items.size() + 1)));
        }
        System.out.printf("Customer source: %d categories, %d items, currency %s%n", sections.size(), items.size(), currency);
        for (var section : sections) {
            System.out.printf("%s | parent %s | %d directly assigned%n", section.id(), section.parentId(),
                    items.stream().filter(item -> item.sectionId().equals(section.id())).count());
        }
        for (var item : items) {
            System.out.printf("%s | %s | %s %s | %s | prep %s | %d option groups | photo %s%n",
                    item.id(), item.dish().name(), currency, item.dish().price(), item.dish().availability(),
                    item.dish().prepMinutes(), item.dish().customizationGroups().size(),
                    Files.isRegularFile(item.photo()) ? item.photo().getFileName() : "missing");
        }
        if (mode.equals("inventory")) {
            return;
        }

        boolean apply = mode.equals("apply");
        // Both modes require the existing backend to have applied its Flyway migrations.
        try (var context = new SpringApplicationBuilder(ServerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=catalog", "--spring.flyway.enabled=false", "--spring.main.banner-mode=off")) {
            Report result = context.getBean(CatalogImportService.class).run(currency, sections, items, apply);
            for (var entry : result.entries()) {
                System.out.printf("%s | %s | %s%n", entry.status(), entry.id(), entry.detail());
            }
            var counts = result.entries().stream().collect(java.util.stream.Collectors.groupingBy(
                    Entry::status, java.util.TreeMap::new, java.util.stream.Collectors.counting()));
            System.out.printf("%s summary %s%n", mode, counts);
            if (!result.successful()) {
                throw new IllegalStateException("Catalog import failed without committing catalog changes.");
            }
        }
    }

    private static Item map(MenuItem item, Path resources, int order) throws Exception {
        Path photos = resources.resolve("images/menu").toRealPath();
        String source = item.image();
        if (source == null || !source.startsWith("/images/menu/")) {
            throw new IllegalArgumentException("Missing or unmanaged image reference for " + item.id());
        }
        Path photo = resources.resolve(source.substring(1)).normalize();
        if (!photo.startsWith(photos) || Files.exists(photo) && !photo.toRealPath().startsWith(photos)) {
            throw new IllegalArgumentException("Photo escapes the Customer menu assets for " + item.id());
        }
        var optionAllergens = new LinkedHashMap<String, List<Allergen>>();
        var groups = item.customizationGroups().stream().map(group -> new Customization(group.id(),
                group.name().english(), Selection.valueOf(group.selection().name()), group.minimum(), group.maximum(),
                group.choices().stream().map(choice -> {
                    if (!choice.contains().isEmpty()) {
                        optionAllergens.put(group.id() + "/" + choice.id(), allergens(choice.contains()));
                    }
                    return new Choice(choice.id(), choice.name().english(), choice.priceAdjustment());
                }).toList())).toList();
        var safety = item.safety();
        var details = new SourceDetails(item.summary().english(), item.spice() == null ? null : item.spice().name(),
                item.highlights().stream().map(Enum::name).sorted().toList(), allergens(safety.contains()),
                allergens(safety.possibleCrossContact()),
                safety.dietaryLabels().stream().map(tag -> Diet.valueOf(tag.name())).sorted().toList(),
                safety.allergenDataComplete(), optionAllergens);
        var dish = new DishInput(null, item.name().english(), item.description().english(),
                Cuisine.valueOf(item.cuisine().name()), item.price(), null,
                item.ingredients().stream().map(value -> value.english()).toList(),
                List.of(), List.of(), item.preparationTime() == null ? null : Math.toIntExact(item.preparationTime().toMinutes()),
                null, Availability.valueOf(item.availability().name()), groups, null, order);
        return new Item(item.id(), item.categoryId(), dish, details, photo);
    }

    private static List<Allergen> allergens(java.util.Set<MenuItem.Allergen> flags) {
        return flags.stream().map(flag -> Allergen.valueOf(flag.name())).sorted().toList();
    }
}
