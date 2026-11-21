package com.roseroots.server.catalog;

import com.roseroots.server.ServerApplication;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ROSE_TEST_DB_URL", matches = "jdbc:postgresql:.+")
class CatalogPostgresTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String DRINKS = "00000000-0000-0000-0000-000000000004";
    private static final String SCHEMA = "rr_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DB_URL = System.getenv("ROSE_TEST_DB_URL");
    private static final String DB_USER = System.getenv("ROSE_TEST_DB_USERNAME");
    private static final String DB_PASSWORD = System.getenv("ROSE_TEST_DB_PASSWORD");
    private static ServletWebServerApplicationContext server;
    @TempDir
    static Path imageDirectory;

    @BeforeAll
    static void start() throws Exception {
        server = startServer();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        try (var connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void seedsCategoriesAndGuardsManagerRoutes() throws Exception {
        try (var client = client()) {
            var list = json(200, request(server, client, "GET", "/categories", null, false));
            for (String seeded : new String[]{"Starters", "Main Courses", "Desserts", "Drinks",
                    "Hot Drinks", "Cold Drinks", "Shakes"}) {
                assertTrue(list.toString().contains("\"" + seeded + "\""));
            }
            assertEquals(401, request(server, client, "GET", "/admin/catalog", null, false).statusCode());
            assertEquals(401, request(server, client, "POST", "/admin/categories",
                    section("Anonymous", null, "VISIBLE"), true).statusCode());
            login(client, "kitchen-test", 403);
            assertEquals(401, request(server, client, "GET", "/admin/catalog", null, false).statusCode());
            login(client, "manager-test", 204);
            assertEquals(403, request(server, client, "POST", "/admin/categories",
                    section("No CSRF", null, "VISIBLE"), false).statusCode());
        }
    }

    @Test
    void createsNestedCategoriesRejectsCyclesAndPublishesVisibilityChanges() throws Exception {
        try (var client = client()) {
            login(client, "manager-test", 204);
            String parent = write(client, 201, "POST", "/admin/categories",
                    section("Seasonal", DRINKS, "VISIBLE")).get("id").asString();
            String child = write(client, 201, "POST", "/admin/categories",
                    section("Seasonal Teas", parent, "VISIBLE")).get("id").asString();
            write(client, 400, "PATCH", "/admin/categories/" + parent,
                    section("Seasonal", child, "VISIBLE"));
            write(client, 409, "POST", "/admin/categories", section("seasonal", DRINKS, "VISIBLE"));
            var updated = section("Seasonal Menu", DRINKS, "HIDDEN");
            updated.put("displayOrder", 5);
            assertEquals(5, write(client, 200, "PATCH", "/admin/categories/" + parent, updated)
                    .get("displayOrder").asInt());
            assertFalse(publicMenu(client).toString().contains(child));
            updated.put("status", "ARCHIVED");
            write(client, 200, "PATCH", "/admin/categories/" + parent, updated);
            updated.put("status", "VISIBLE");
            write(client, 200, "PATCH", "/admin/categories/" + parent, updated);
            assertTrue(publicMenu(client).toString().contains(child));
        }
    }

    @Test
    void persistsDishesAuditAndOptionsAcrossSpringBootRestart() throws Exception {
        String itemId;
        try (var client = client()) {
            login(client, "manager-test", 204);
            var dish = dish();
            dish.put("price", -1);
            write(client, 400, "POST", "/admin/menu-items", dish);
            dish.put("price", 210.25);
            itemId = write(client, 201, "POST", "/admin/menu-items", dish).get("id").asString();
            assertTrue(publicMenu(client).toString().contains(itemId));

            var catalog = server.getBean(CatalogService.class);
            var tx = new TransactionTemplate(server.getBean(PlatformTransactionManager.class));
            UUID uuid = UUID.fromString(itemId);
            var accepted = tx.execute(status -> catalog.requireOrderable(uuid));

            dish.put("description", "Updated description");
            write(client, 200, "PATCH", "/admin/menu-items/" + itemId, dish);
            var changes = JSON.createObjectNode().put("price", 225.50).put("availability", "SOLD_OUT")
                    .put("reason", "Updated ingredient cost");
            write(client, 200, "PATCH", "/admin/menu-items/" + itemId + "/trading", changes);
            assertThrows(ResponseStatusException.class, () -> tx.execute(status -> catalog.requireOrderable(uuid)));
            assertEquals("210.25", accepted.price().toPlainString());
            assertTrue(publicMenu(client).toString().contains("SOLD_OUT"));
            write(client, 200, "PATCH", "/admin/menu-items/" + itemId + "/trading",
                    JSON.createObjectNode().put("archived", true).put("availability", "AVAILABLE"));
            assertFalse(publicMenu(client).toString().contains(itemId));
            assertThrows(ResponseStatusException.class, () -> tx.execute(status -> catalog.requireOrderable(uuid)));
            write(client, 200, "PATCH", "/admin/menu-items/" + itemId + "/trading",
                    JSON.createObjectNode().put("archived", false).put("availability", "SOLD_OUT"));
        }

        server.close();
        server = startServer();
        try (var client = client()) {
            var saved = publicMenu(client).get("items").valueStream()
                    .filter(item -> itemId.equals(item.get("id").asString())).findFirst().orElseThrow();
            assertEquals(itemId, saved.get("id").asString());
            assertEquals("Updated description", saved.get("description").asString());
            assertEquals(225.50, saved.get("price").asDouble());
            assertEquals("SOLD_OUT", saved.get("availability").asString());
            assertEquals("Honey", saved.get("customizationGroups").get(0).get("choices").get(0).get("name").asString());
            assertEquals(1, saved.get("ingredients").size());
        }
        try (var connection = DriverManager.getConnection(schemaUrl(), DB_USER, DB_PASSWORD);
             var statement = connection.prepareStatement("SELECT actor, reason, changed_at FROM catalog_item_audit WHERE item_id = ?")) {
            statement.setObject(1, UUID.fromString(itemId));
            try (var records = statement.executeQuery()) {
                int count = 0;
                boolean foundReason = false;
                while (records.next()) {
                    count++;
                    assertEquals("manager-test", records.getString("actor"));
                    assertNotNull(records.getTimestamp("changed_at"));
                    foundReason |= "Updated ingredient cost".equals(records.getString("reason"));
                }
                assertEquals(3, count);
                assertTrue(foundReason);
            }
        }
    }

    @Test
    void rejectsUnauthorizedUnsupportedAndOversizedPhotoUploads() throws Exception {
        try (var client = client()) {
            assertEquals(401, upload(client, image("png", 20, 10), true).statusCode());
            login(client, "kitchen-test", 403);
            assertEquals(401, upload(client, image("png", 20, 10), true).statusCode());
            login(client, "manager-test", 204);
            assertEquals(403, upload(client, image("png", 20, 10), false).statusCode());
            assertEquals(415, upload(client, new byte[]{1, 2, 3}, true).statusCode());
            assertEquals(415, upload(client, image("gif", 20, 10), true).statusCode());
            assertEquals(413, upload(client, image("png", 6001, 1), true).statusCode());
            var large = new byte[MenuImageService.UPLOAD_LIMIT + 1];
            var service = server.getBean(MenuImageService.class);
            assertEquals(413, assertThrows(ResponseStatusException.class,
                    () -> service.upload(new ByteArrayInputStream(large), -1, "manager-test")).getStatusCode().value());
        }
    }

    @Test
    void persistsSafeImageMetadataAndReplacementWithoutDamagingPreviouslySavedPhoto() throws Exception {
        String photoPath;
        String replacement;
        String dishId;
        byte[] savedBytes;
        try (var client = client()) {
            login(client, "manager-test", 204);
            var response = upload(client, image("png", 220, 150), true);
            var metadata = json(201, response);
            String id = metadata.get("id").asString();
            photoPath = metadata.get("imageReference").asString();
            assertEquals("/api/v1/menu-images/" + id, photoPath);
            assertEquals(photoPath, response.headers().firstValue("Location").orElseThrow());
            assertEquals("image/jpeg", metadata.get("contentType").asString());
            assertEquals(220, metadata.get("width").asInt());
            assertTrue(Files.isRegularFile(imageDirectory.resolve(id + ".jpg")));
            assertEquals(150, ImageIO.read(new ByteArrayInputStream(photo(client, photoPath))).getHeight());
            var dish = dish().put("name", "Photographed dish").put("imageReference", photoPath);
            dishId = write(client, 201, "POST", "/admin/menu-items", dish).get("id").asString();
            assertEquals(415, upload(client, new byte[]{1}, true).statusCode());
            assertTrue(publicMenu(client).toString().contains(photoPath));
            replacement = json(201, upload(client, image("jpeg", 48, 32), true)).get("imageReference").asString();
            assertFalse(publicMenu(client).toString().contains(replacement));
            dish.put("imageReference", "/api/v1/menu-images/../../private.jpg");
            write(client, 400, "PATCH", "/admin/menu-items/" + dishId, dish);
            assertTrue(publicMenu(client).toString().contains(photoPath));
            dish.put("imageReference", replacement).put("price", -1);
            write(client, 400, "PATCH", "/admin/menu-items/" + dishId, dish);
            assertTrue(publicMenu(client).toString().contains(photoPath));
            dish.put("price", 130);
            write(client, 200, "PATCH", "/admin/menu-items/" + dishId, dish);
            assertTrue(publicMenu(client).toString().contains(replacement));
            assertFalse(publicMenu(client).toString().contains(photoPath));
            photo(client, photoPath);
            savedBytes = photo(client, replacement);
        }
        server.close();
        server = startServer();
        try (var client = client()) {
            assertArrayEquals(savedBytes, photo(client, replacement));
            assertTrue(publicMenu(client).toString().contains(replacement));
            login(client, "manager-test", 204);
            write(client, 200, "PATCH", "/admin/menu-items/" + dishId,
                    dish().put("name", "Photographed dish").putNull("imageReference"));
            assertFalse(publicMenu(client).toString().contains(replacement));
        }
        try (var connection = DriverManager.getConnection(schemaUrl(), DB_USER, DB_PASSWORD);
             var statement = connection.prepareStatement("SELECT storage_reference, created_by, file_size FROM catalog_menu_image WHERE id = ?")) {
            UUID id = UUID.fromString(replacement.substring(replacement.lastIndexOf('/') + 1));
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(id + ".jpg", row.getString(1));
                assertEquals("manager-test", row.getString(2));
                assertEquals(savedBytes.length, row.getLong(3));
            }
        }
    }

    @Test
    void exposesSafePublicBrandingAndProtectsManagerOperations() throws Exception {
        try (var client = client()) {
            var brand = json(200, request(server, client, "GET", "/settings/public", null, false));
            assertEquals("KES", brand.get("currency").asString());
            assertFalse(brand.has("version"));
            assertFalse(brand.has("actor"));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(logo(client, "/settings/logo/default", 200))));
            logo(client, "/settings/logo/" + UUID.randomUUID(), 404);
            assertEquals(401, request(server, client, "GET", "/admin/settings", null, false).statusCode());
            assertEquals(401, request(server, client, "PATCH", "/admin/settings", branding(0, null), true).statusCode());
            login(client, "kitchen-test", 403);
            assertEquals(401, request(server, client, "PATCH", "/admin/settings", branding(0, null), true).statusCode());
            assertEquals(401, upload(client, "/admin/settings/logo", new byte[0], true).statusCode());
            login(client, "manager-test", 204);
            assertEquals(403, request(server, client, "PATCH", "/admin/settings", branding(0, null), false).statusCode());
            assertEquals(403, upload(client, "/admin/settings/logo", new byte[0], false).statusCode());
        }
    }

    @Test
    void persistsBrandingAndLogoAcrossRestartAndAuditsOnlyChanges() throws Exception {
        String replacement;
        byte[] savedLogo;
        try (var client = client()) {
            login(client, "manager-test", 204);
            var initial = json(200, request(server, client, "GET", "/admin/settings", null, false));
            long version = initial.get("version").asLong();
            assertEquals("Rose & Roots", initial.get("brand").get("name").asString());
            assertEquals(3, initial.get("themes").size());
            assertEquals(415, upload(client, "/admin/settings/logo", new byte[0], true).statusCode());
            assertEquals(415, upload(client, "/admin/settings/logo", image("gif", 20, 10), true).statusCode());
            assertEquals(413, upload(client, "/admin/settings/logo", image("png", 6001, 1), true).statusCode());
            var png = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(new BufferedImage(12, 5, BufferedImage.TYPE_INT_ARGB), "png", png));
            String reference = json(201, upload(client, "/admin/settings/logo", png.toByteArray(), true))
                    .get("logoReference").asString();
            assertEquals(initial.get("brand"), json(200, request(server, client, "GET", "/settings/public", null, false)));
            var decoded = ImageIO.read(new ByteArrayInputStream(logo(client, reference.substring(7), 200)));
            assertEquals(12, decoded.getWidth());
            assertEquals(5, decoded.getHeight());
            assertEquals(0, decoded.getRGB(0, 0) >>> 24);
            write(client, 400, "PATCH", "/admin/settings", branding(version, reference).put("name", " "));
            write(client, 400, "PATCH", "/admin/settings", branding(version, reference).put("email", "invalid"));
            write(client, 400, "PATCH", "/admin/settings", branding(version, "https://example.invalid/image.png"));
            assertEquals(initial.get("brand"), json(200, request(server, client, "GET", "/settings/public", null, false)));
            var update = branding(version, reference);
            var first = write(client, 200, "PATCH", "/admin/settings", update);
            long next = first.get("version").asLong();
            assertTrue(next > version);
            write(client, 409, "PATCH", "/admin/settings", branding(version, null));
            assertEquals(next, write(client, 200, "PATCH", "/admin/settings", update.put("version", next))
                    .get("version").asLong());
            replacement = json(201, upload(client, "/admin/settings/logo", image("jpeg", 30, 20), true))
                    .get("logoReference").asString();
            var second = write(client, 200, "PATCH", "/admin/settings", branding(next, replacement));
            assertEquals(replacement, second.get("brand").get("logoReference").asString());
            savedLogo = logo(client, replacement.substring(7), 200);
            assertEquals(30, ImageIO.read(new ByteArrayInputStream(savedLogo)).getWidth());
        }
        server.close();
        server = startServer();
        try (var client = client()) {
            var brand = json(200, request(server, client, "GET", "/settings/public", null, false));
            assertEquals("Rose & Roots Test", brand.get("name").asString());
            assertEquals("WINE_GOLD", brand.get("theme").get("preset").asString());
            assertEquals(replacement, brand.get("logoReference").asString());
            assertArrayEquals(savedLogo, logo(client, replacement.substring(7), 200));
            login(client, "manager-test", 204);
            long version = json(200, request(server, client, "GET", "/admin/settings", null, false)).get("version").asLong();
            assertTrue(write(client, 200, "PATCH", "/admin/settings", branding(version, null))
                    .get("brand").get("logoReference").isNull());
        }
        try (var connection = DriverManager.getConnection(schemaUrl(), DB_USER, DB_PASSWORD);
             var statement = connection.createStatement();
             var audit = statement.executeQuery("SELECT actor, before_settings, after_settings FROM restaurant_settings_audit")) {
            int count = 0;
            while (audit.next()) {
                assertEquals("manager-test", audit.getString(1));
                assertNotEquals(audit.getString(2), audit.getString(3));
                count++;
            }
            assertEquals(3, count);
        }
    }

    private static ObjectNode branding(long version, String reference) {
        return JSON.createObjectNode().put("version", version).put("name", "Rose & Roots Test")
                .put("tagline", "Test tagline").put("description", "Test description").put("telephone", "+254 12345")
                .put("email", "contact@example.invalid").put("address", "Test address")
                .put("theme", "WINE_GOLD").put("logoReference", reference);
    }

    private static byte[] logo(HttpClient client, String path, int expected) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri(server, path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expected, response.statusCode());
        if (expected == 200) {
            assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        }
        return response.body();
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }

    private static HttpResponse<String> upload(HttpClient client, byte[] body, boolean csrf) throws Exception {
        return upload(client, "/admin/menu-images", body, csrf);
    }

    private static HttpResponse<String> upload(HttpClient client, String path, byte[] body, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (csrf) {
            builder.header("X-CSRF-TOKEN", request(server, client, "GET", "/admin/csrf", null, false).body());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] photo(HttpClient client, String reference) throws Exception {
        var request = HttpRequest.newBuilder(uri(server, "/menu").resolve(reference)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        return response.body();
    }

    private static ObjectNode dish() {
        var item = JSON.createObjectNode().put("categoryId", DRINKS).put("name", "Honey Ginger Tea")
                .put("description", "Freshly brewed ginger tea").put("cuisine", "INTERNATIONAL").put("price", 210.25)
                .put("servingSize", "250 ml").put("prepMinutes", 6).put("kitchenStation", "Beverages")
                .put("availability", "AVAILABLE").put("displayOrder", 10);
        item.putArray("ingredients").add("Ginger");
        item.putArray("allergens");
        item.putArray("dietaryTags").add("VEGETARIAN");
        var extra = item.putArray("customizationGroups").addObject().put("id", "sweetener").put("name", "Sweetener")
                .put("selection", "EXTRA").put("minimum", 0).put("maximum", 1);
        extra.putArray("choices").addObject().put("id", "honey").put("name", "Honey").put("priceAdjustment", 10);
        return item;
    }

    private static ObjectNode section(String name, String parent, String state) {
        return JSON.createObjectNode().put("name", name).put("parentId", parent)
                .put("displayOrder", 60).put("status", state);
    }

    private static ServletWebServerApplicationContext startServer() {
        return startServer(imageDirectory);
    }

    private static ServletWebServerApplicationContext startServer(Path storage) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ServerApplication.class).run(
                "--spring.profiles.active=catalog", "--server.port=0", "--server.address=127.0.0.1",
                "--ROSE_DB_URL=" + schemaUrl(),
                "--ROSE_DB_USERNAME=" + DB_USER, "--ROSE_DB_PASSWORD=" + DB_PASSWORD,
                "--spring.flyway.schemas=" + SCHEMA,
                "--rose.catalog.image-directory=" + storage,
                "--rose.admin.manager.username=manager-test", "--rose.admin.manager.password-hash=" + HASH,
                "--rose.admin.kitchen.username=kitchen-test", "--rose.admin.kitchen.password-hash=" + HASH);
    }

    @Test
    void customerImportIsReadOnlyUntilAppliedAndPreservesManagerEditsAndPhotoIdentity() throws Exception {
        var photo = imageDirectory.resolve("import-subject.png");
        Files.write(photo, image("png", 111, 23));
        var sections = java.util.List.of(new CatalogImportService.Section("drinks", null, "Drinks", 40),
                new CatalogImportService.Section("hot-drinks", "drinks", "Hot Drinks", 10));
        var details = new CatalogData.SourceDetails("Source summary", "MILD", java.util.List.of(),
                java.util.List.of(CatalogData.Allergen.MILK), java.util.List.of(CatalogData.Allergen.NUTS),
                java.util.List.of(CatalogData.Diet.VEGETARIAN), true, java.util.Map.of());
        var input = new CatalogData.DishInput(null, "Imported test tea", "Original description",
                CatalogData.Cuisine.INTERNATIONAL, new java.math.BigDecimal("319.25"), null,
                java.util.List.of("Tea leaves"), java.util.List.of(), java.util.List.of(), 8, null,
                CatalogData.Availability.AVAILABLE, java.util.List.of(new CatalogData.Customization(
                "extras", "Extras", CatalogData.Selection.EXTRA, 0, 1, java.util.List.of(
                new CatalogData.Choice("milk", "Milk", new java.math.BigDecimal("30.00"))))), null, 10);
        var second = new CatalogData.DishInput(null, "Imported test coffee", input.description(), input.cuisine(),
                input.price(), null, input.ingredients(), input.allergens(), input.dietaryTags(), null, null,
                input.availability(), java.util.List.of(), null, 20);
        var items = java.util.List.of(new CatalogImportService.Item("integration-tea", "hot-drinks", input, details, photo),
                new CatalogImportService.Item("integration-coffee", "hot-drinks", second, details, photo));
        var importer = server.getBean(CatalogImportService.class);
        assertFalse(importer.run("KES", sections, java.util.List.of(items.getFirst(), items.getFirst()), true).successful());
        assertTrue(importer.run("KES", sections, items, false).successful());
        assertFalse(server.getBean(CatalogService.class).snapshot(false).items()
                .stream().anyMatch(row -> "integration-tea".equals(row.customerSourceId())));
        assertTrue(importer.run("KES", sections, items, true).successful());
        var catalog = server.getBean(CatalogService.class);
        var imported = catalog.snapshot(false).items().stream()
                .filter(row -> "integration-tea".equals(row.customerSourceId())).findFirst().orElseThrow();
        var sibling = catalog.snapshot(true).items().stream()
                .filter(row -> "integration-coffee".equals(row.customerSourceId())).findFirst().orElseThrow();
        assertEquals("00000000-0000-0000-0000-000000000005", imported.categoryId().toString());
        assertEquals(new java.math.BigDecimal("319.25"), imported.price());
        assertEquals(new java.math.BigDecimal("30.00"),
                imported.customizationGroups().getFirst().choices().getFirst().priceAdjustment());
        assertEquals(imported.imageReference(), sibling.imageReference());
        assertEquals(details.reportedCrossContact(), imported.sourceDetails().reportedCrossContact());
        assertNull(imported.servingSize());
        assertNull(sibling.prepMinutes());
        catalog.updateTrading(imported.id(), new CatalogData.TradingUpdate(new java.math.BigDecimal("350.00"),
                CatalogData.Availability.SOLD_OUT, null, "Test manager edit"), "manager-test");
        assertTrue(importer.run("KES", sections, items, true).entries().stream()
                .allMatch(entry -> entry.status().equals("SKIPPED")));
        server.close();
        server = startServer();
        try (var client = client()) {
            var restored = publicMenu(client).get("items").valueStream().filter(row ->
                    "integration-tea".equals(row.get("customerSourceId").asString(""))).findFirst().orElseThrow();
            assertEquals(350, restored.get("price").asInt());
            assertEquals("SOLD_OUT", restored.get("availability").asString());
            photo(client, restored.get("imageReference").asString());
        }
    }

    @Test
    void storageFailureRollsBackCatalogAndValidSourceCanBeRetried() throws Exception {
        var source = imageDirectory.resolve("rollback-subject.png");
        Files.write(source, image("png", 119, 29));
        var broken = imageDirectory.resolve("not-a-directory");
        Files.writeString(broken, "test");
        var sections = java.util.List.of(new CatalogImportService.Section("rollback-section", null, "Rollback section", 90));
        var dish = new CatalogData.DishInput(null, "Rollback dish", "Rollback description",
                CatalogData.Cuisine.INTERNATIONAL, java.math.BigDecimal.TEN, null, java.util.List.of(),
                java.util.List.of(), java.util.List.of(), null, null, CatalogData.Availability.AVAILABLE,
                java.util.List.of(), null, 10);
        var items = java.util.List.of(new CatalogImportService.Item("rollback-test", "rollback-section", dish, null, source));
        server.close();
        server = startServer(broken);
        assertFalse(server.getBean(CatalogImportService.class).run("KES", sections, items, true).successful());
        assertFalse(server.getBean(CatalogService.class).snapshot(false).categories().stream()
                .anyMatch(section -> section.name().equals("Rollback section")));
        server.close();
        server = startServer();
        assertTrue(server.getBean(CatalogImportService.class).run("KES", sections, items, true).successful());
        assertTrue(server.getBean(CatalogService.class).snapshot(false).items().stream()
                .anyMatch(row -> "rollback-test".equals(row.customerSourceId()) && row.imageReference() != null));
    }

    private static String schemaUrl() {
        return DB_URL + (DB_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    private static JsonNode publicMenu(HttpClient client) throws Exception {
        return json(200, request(server, client, "GET", "/menu", null, false));
    }

    private static void login(HttpClient client, String username, int expected) throws Exception {
        String token = request(server, client, "GET", "/admin/csrf", null, false).body();
        var request = HttpRequest.newBuilder(uri(server, "/admin/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD)).build();
        assertEquals(expected, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private static JsonNode write(HttpClient client, int expected, String method, String path, ObjectNode body)
            throws Exception {
        return json(expected, request(server, client, method, path, body, true));
    }

    private static JsonNode json(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> request(ServletWebServerApplicationContext context, HttpClient client,
                                                 String method, String path, ObjectNode body, boolean csrf)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(context, path)).timeout(Duration.ofSeconds(15));
        if (csrf) {
            request.header("X-CSRF-TOKEN", request(context, client, "GET", "/admin/csrf", null, false).body());
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(ServletWebServerApplicationContext context, String path) {
        return URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/api/v1" + path);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }
}
