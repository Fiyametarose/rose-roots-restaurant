package com.roseroots.server.catalog;

import jakarta.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.roseroots.server.catalog.CatalogData.*;

@Service
@Profile("catalog")
public class CatalogImportService {
    private static final String ACTOR = "customer-catalog-import";
    private static final List<String> SEED = List.of("starters", "main-courses", "desserts", "drinks",
            "hot-drinks", "cold-drinks", "shakes");
    private final CatalogService catalog;
    private final CategoryRepository categories;
    private final DishRepository dishes;
    private final MenuImageService photos;
    private final MenuImageRepository images;
    private final Validator validator;
    private final TransactionTemplate transactions;

    CatalogImportService(CatalogService catalog, CategoryRepository categories, DishRepository dishes,
                         MenuImageService photos, MenuImageRepository images, Validator validator,
                         PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.categories = categories;
        this.dishes = dishes;
        this.photos = photos;
        this.images = images;
        this.validator = validator;
        transactions = new TransactionTemplate(transactionManager);
    }

    public Report run(String currency, List<Section> sections, List<Item> items, boolean apply) {
        var failures = new ArrayList<Entry>();
        var prepared = validateInventory(sections, items, failures);
        if (!failures.isEmpty()) {
            return new Report(apply, false, failures);
        }

        var report = new ArrayList<Entry>();
        String[] processing = {"catalog"};
        try {
            return transactions.execute(status -> {
                if (apply) {
                    categories.lockCatalog();
                }
                if (!catalog.currency().equals(currency)) {
                    return new Report(apply, false, List.of(new Entry("currency", "FAILED",
                            "Customer currency differs from the configured catalog currency")));
                }
                importInventory(sections, prepared, apply, processing, report);
                return new Report(apply, true, report);
            });
        } catch (ImportConflict failure) {
            return new Report(apply, false, List.of(new Entry(processing[0], "CONFLICT", failure.getMessage())));
        } catch (RuntimeException failure) {
            return new Report(apply, false, List.of(new Entry(processing[0], "FAILED",
                    "Import transaction rolled back: " + failure.getClass().getSimpleName())));
        }
    }

    private List<Prepared> validateInventory(List<Section> sections, List<Item> items, List<Entry> failures) {
        var known = new HashSet<String>();
        var sectionNames = new HashSet<String>();
        for (var section : sections) {
            try {
                requireId(section.id());
                validate(new CategoryInput(null, section.name(), section.order(), CategoryStatus.VISIBLE));
                if (section.parentId() != null && !known.contains(section.parentId())) {
                    throw new IllegalArgumentException("Parent must precede its child");
                }
                if (!known.add(section.id()) || !sectionNames.add(section.parentId() + "/" + normalized(section.name()))) {
                    throw new IllegalArgumentException("Duplicate section");
                }
            } catch (IllegalArgumentException failure) {
                failures.add(new Entry(section.id(), "FAILED", failure.getMessage()));
            }
        }
        var itemIds = new HashSet<String>();
        var itemNames = new HashSet<String>();
        var prepared = new ArrayList<Prepared>();
        for (var item : items) {
            try {
                requireId(item.id());
                if (!known.contains(item.sectionId())) {
                    throw new IllegalArgumentException("Unknown section");
                }
                validate(input(item.dish(), stable("category", item.sectionId()), null));
                CatalogService.validateCustomizations(item.dish().customizationGroups());
                if (!itemIds.add(item.id()) || !itemNames.add(item.sectionId() + "/" + normalized(item.dish().name()))) {
                    throw new IllegalArgumentException("Duplicate dish");
                }
                byte[] bytes;
                try (var stream = Files.newInputStream(item.photo())) {
                    bytes = stream.readNBytes(MenuImageService.UPLOAD_LIMIT + 1);
                }
                if (bytes.length > MenuImageService.UPLOAD_LIMIT) {
                    throw new IllegalArgumentException("Photo exceeds the upload limit");
                }
                MenuImageService.decode(bytes);
                prepared.add(new Prepared(item, bytes, digest(bytes)));
            } catch (IOException failure) {
                failures.add(new Entry(item.id(), "FAILED", "Missing or unreadable source photo"));
            } catch (org.springframework.web.server.ResponseStatusException failure) {
                failures.add(new Entry(item.id(), "FAILED", failure.getReason()));
            } catch (IllegalArgumentException failure) {
                failures.add(new Entry(item.id(), "FAILED", failure.getMessage()));
            }
        }
        return prepared;
    }

    private void importInventory(List<Section> sections, List<Prepared> inventory, boolean apply,
                                 String[] processing, List<Entry> report) {
        var existing = categories.findAll();
        var mapped = new HashMap<String, UUID>();
        for (var section : sections) {
            processing[0] = section.id();
            UUID parent = mapped.get(section.parentId());
            UUID expected = categoryId(section.id());
            var match = existing.stream().filter(row -> expected.equals(row.id)).findFirst()
                    .or(() -> existing.stream().filter(row -> Objects.equals(parent, row.parentId)
                            && normalized(section.name()).equals(normalized(row.name))).findFirst()).orElse(null);
            if (match != null) {
                if (!Objects.equals(parent, match.parentId) || match.status == CategoryStatus.ARCHIVED) {
                    throw new ImportConflict("Existing category hierarchy or status conflicts with the source; retained");
                }
                mapped.put(section.id(), match.id);
                report.add(new Entry(section.id(), "SKIPPED", "Existing category retained"));
            } else {
                mapped.put(section.id(), expected);
                if (apply) {
                    catalog.saveCategory(expected, new CategoryInput(parent, section.name(), section.order(),
                            CategoryStatus.VISIBLE), true);
                }
                report.add(new Entry(section.id(), apply ? "CREATED" : "WOULD_CREATE", "Category"));
            }
        }

        var current = dishes.findAll();
        var plannedPhotos = new HashSet<String>();
        for (var prepared : inventory) {
            var item = prepared.item();
            processing[0] = item.id();
            UUID id = stable("dish", item.id());
            UUID section = mapped.get(item.sectionId());
            if (current.stream().anyMatch(row -> item.id().equals(row.customerSourceId))) {
                report.add(new Entry(item.id(), "SKIPPED", "Previously imported dish retained"));
                continue;
            }
            if (current.stream().anyMatch(row -> id.equals(row.id) || section.equals(row.categoryId)
                    && normalized(item.dish().name()).equals(normalized(row.name)))) {
                report.add(new Entry(item.id(), "CONFLICT", "Existing dish retained; resolve identity manually"));
                continue;
            }
            String reference = null;
            var stored = images.findBySourceSha256(prepared.digest());
            boolean newPhoto = stored.isEmpty() && plannedPhotos.add(prepared.digest());
            if (apply) {
                reference = stored.isPresent() ? existingPhoto(stored.get()) : upload(prepared);
                catalog.saveDish(id, input(item.dish(), section, reference), ACTOR, item.id(), item.details());
            }
            report.add(new Entry(item.id(), apply ? "CREATED" : "WOULD_CREATE",
                    newPhoto ? "Dish and photo" : "Dish with shared photo"));
        }
    }

    private String existingPhoto(MenuImage image) {
        try {
            photos.read(image.id);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        return MenuImageService.PUBLIC_PATH + image.id;
    }

    private String upload(Prepared prepared) {
        try {
            return photos.upload(new ByteArrayInputStream(prepared.bytes()), prepared.bytes().length,
                    ACTOR, prepared.digest()).imageReference();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private void validate(Object value) {
        var violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted()
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,99}")) {
            throw new IllegalArgumentException("Invalid Customer identifier");
        }
    }

    private static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static UUID categoryId(String id) {
        int seedIndex = SEED.indexOf(id);
        return seedIndex < 0 ? stable("category", id)
                : UUID.fromString(String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", seedIndex + 1));
    }

    private static UUID stable(String type, String id) {
        return UUID.nameUUIDFromBytes(("rose-roots/customer/" + type + "/" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static DishInput input(DishInput source, UUID category, String image) {
        return new DishInput(category, source.name(), source.description(), source.cuisine(), source.price(),
                source.servingSize(), source.ingredients(), source.allergens(), source.dietaryTags(),
                source.prepMinutes(), source.kitchenStation(), source.availability(), source.customizationGroups(),
                image, source.displayOrder());
    }

    public record Section(String id, String parentId, String name, int order) {}
    public record Item(String id, String sectionId, DishInput dish, SourceDetails details, Path photo) {}
    public record Entry(String id, String status, String detail) {}
    public record Report(boolean apply, boolean successful, List<Entry> entries) {
        public Report {
            entries = List.copyOf(entries);
        }
    }
    private record Prepared(Item item, byte[] bytes, String digest) {}
    private static final class ImportConflict extends RuntimeException {
        ImportConflict(String message) { super(message); }
    }
}
