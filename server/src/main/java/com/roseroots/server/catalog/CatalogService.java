package com.roseroots.server.catalog;

import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.roseroots.server.catalog.CatalogData.*;

@Service
@Profile("catalog")
public class CatalogService {
    private final CategoryRepository categories;
    private final DishRepository dishes;
    private final DishAuditRepository audit;
    private final String currency;
    private final MenuImageService images;
    private final DishPreparation preparation;

    CatalogService(CategoryRepository categories, DishRepository dishes, DishAuditRepository audit, MenuImageService images,
                   DishPreparation preparation,
                   @Value("${rose.catalog.currency}") String currency) {
        this.categories = categories;
        this.dishes = dishes;
        this.audit = audit;
        this.images = images;
        this.preparation = preparation;
        this.currency = Currency.getInstance(currency).getCurrencyCode();
    }

    String currency() {
        return currency;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(boolean published) {
        var tree = tree();
        var visible = dishes.findAll().stream()
                .filter(item -> !published || !item.archived && pathHasStatus(item.categoryId, tree, true))
                .sorted(Comparator.comparingInt((DishEntity item) -> item.displayOrder)
                        .thenComparing(item -> item.name, String.CASE_INSENSITIVE_ORDER).thenComparing(item -> item.id))
                .toList();
        var profiles = preparation.read(visible.stream().map(item -> item.id).toList(), !published);
        var items = visible.stream().map(item -> item.toView(profiles.get(item.id), !published)).toList();
        var counts = items.stream().collect(Collectors.groupingBy(Dish::categoryId, Collectors.counting()));
        var sections = tree.values().stream()
                .filter(category -> !published || pathHasStatus(category.id, tree, true))
                .sorted(Comparator.comparingInt((CategoryEntity category) -> category.displayOrder)
                        .thenComparing(category -> category.name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(category -> category.id))
                .map(category -> category.toView(counts.getOrDefault(category.id, 0L))).toList();
        return new Snapshot(currency, sections, items);
    }

    @Transactional
    public Category saveCategory(UUID id, CategoryInput input) {
        return saveCategory(id, input, false);
    }

    Category saveCategory(UUID id, CategoryInput input, boolean createWithId) {
        categories.lockCatalog();
        var tree = tree();
        var category = id == null || createWithId ? new CategoryEntity() : findCategory(id, tree);
        String name = input.name().strip();
        var visited = new HashSet<UUID>();
        for (UUID parent = input.parentId(); parent != null; parent = findCategory(parent, tree).parentId) {
            if (parent.equals(id) || !visited.add(parent)) {
                throw bad("A section cannot be placed inside itself or one of its descendants.");
            }
        }
        if (input.parentId() != null && !input.parentId().equals(category.parentId)
                && !pathHasStatus(input.parentId(), tree, false)) {
            throw bad("Choose a parent outside the archived sections.");
        }
        boolean duplicate = tree.values().stream().anyMatch(other -> !other.id.equals(id)
                && java.util.Objects.equals(other.parentId, input.parentId()) && other.name.equalsIgnoreCase(name));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A sibling section already has that name.");
        }
        category.id = id == null ? UUID.randomUUID() : id;
        category.name = name;
        category.parentId = input.parentId();
        category.displayOrder = input.displayOrder();
        category.status = input.status();
        categories.save(category);
        return category.toView(dishes.countByCategoryId(category.id));
    }

    @Transactional
    public Dish saveDish(UUID id, DishInput input, String actor) {
        return saveDish(id, input, actor, null, null);
    }

    Dish saveDish(UUID id, DishInput input, String actor, String sourceId, SourceDetails sourceDetails) {
        categories.lockCatalog();
        boolean create = id == null || sourceId != null;
        var dish = create ? new DishEntity() : findDish(id);
        if (!create && (input.expectedVersion() != null && input.expectedVersion() != dish.editVersion
                || input.preparation() != null && input.expectedVersion() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reload this dish before saving its preparation settings.");
        }
        if (sourceId == null && dish.customerSourceId == null
                && (input.servingSize() == null || input.servingSize().isBlank())) {
            throw bad("Serving size is required.");
        }
        if (create && sourceId == null && input.preparation() == null) {
            throw bad("A preparation station and base duration are required for a new dish.");
        }
        if (sourceId == null && input.preparation() == null && (input.kitchenStation() != null || input.prepMinutes() != null)) {
            throw bad("Use the dish preparation profile to set station and duration.");
        }
        var tree = tree();
        findCategory(input.categoryId(), tree);
        if (!input.categoryId().equals(dish.categoryId) && !pathHasStatus(input.categoryId(), tree, false)) {
            throw bad("Choose a section outside the archived categories.");
        }
        validateCustomizations(input.customizationGroups());
        images.validateReference(input.imageReference(), dish.imageReference);
        Dish before = create ? null : dish.toView();
        var current = create ? null : preparation.read(List.of(dish.id), true).get(dish.id);
        dish.id = id == null ? UUID.randomUUID() : id;
        dish.apply(input);
        if (!create) dish.editVersion++;
        if (sourceId != null) {
            dish.customerSourceId = sourceId;
            dish.sourceDetails = sourceDetails;
        }
        dishes.saveAndFlush(dish);
        var profile = preparation.save(dish.id, input.preparation(), dish.customizationGroups, current, actor);
        if (before != null) {
            recordChange(before, dish, actor, null);
        }
        return dish.toView(profile, true);
    }

    @Transactional
    public Dish updateTrading(UUID id, TradingUpdate update, String actor) {
        categories.lockCatalog();
        var dish = findDish(id);
        Dish before = dish.toView();
        if (update.price() == null && update.availability() == null && update.archived() == null) {
            throw bad("Provide a price, availability or archive status.");
        }
        if (update.price() != null) {
            dish.price = update.price().setScale(2);
        }
        if (update.availability() != null) {
            dish.availability = update.availability();
        }
        if (update.archived() != null) {
            dish.archived = update.archived();
        }
        dish.editVersion++;
        recordChange(before, dish, actor, update.reason() == null ? null : update.reason().strip());
        return dish.toView(preparation.read(List.of(id), true).get(id), true);
    }

    /** Order acceptance must copy the returned price and details into its own immutable snapshot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Dish requireOrderable(UUID id) {
        categories.lockCatalog();
        return orderableDish(id);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Dish readOrderableSnapshot(UUID id) {
        return orderableDish(id);
    }

    private Dish orderableDish(UUID id) {
        var dish = findDish(id);
        if (dish.archived || dish.availability != Availability.AVAILABLE
                || !pathHasStatus(dish.categoryId, tree(), true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This dish is unavailable for new orders.");
        }
        return dish.toView();
    }

    private void recordChange(Dish before, DishEntity after, String actor, String reason) {
        if (before.price().compareTo(after.price) != 0 || before.availability() != after.availability
                || before.archived() != after.archived) {
            audit.save(new DishAudit(before, after, actor, reason));
        }
    }

    static void validateCustomizations(List<Customization> groups) {
        var groupIds = new HashSet<String>();
        for (var group : groups) {
            if (!groupIds.add(group.id()) || group.minimum() > group.maximum()
                    || group.maximum() > group.choices().size()
                    || group.choices().stream().map(Choice::id).distinct().count() != group.choices().size()) {
                throw bad("Customization IDs must be unique and selection limits must fit the available choices.");
            }
        }
    }

    private Map<UUID, CategoryEntity> tree() {
        return categories.findAll().stream().collect(Collectors.toMap(category -> category.id, Function.identity()));
    }

    private static boolean pathHasStatus(UUID id, Map<UUID, CategoryEntity> tree, boolean published) {
        var visited = new HashSet<UUID>();
        for (UUID current = id; current != null; ) {
            var category = tree.get(current);
            if (category == null || !visited.add(current) || category.status == CategoryStatus.ARCHIVED
                    || published && category.status != CategoryStatus.VISIBLE) {
                return false;
            }
            current = category.parentId;
        }
        return true;
    }

    private static CategoryEntity findCategory(UUID id, Map<UUID, CategoryEntity> tree) {
        var category = tree.get(id);
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found.");
        }
        return category;
    }

    private DishEntity findDish(UUID id) {
        return dishes.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dish not found."));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
