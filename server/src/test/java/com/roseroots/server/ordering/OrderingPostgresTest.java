package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.service.KitchenSync;
import com.roseroots.kitchen.service.http.LoopbackKitchenClient;
import com.roseroots.server.ServerApplication;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static com.roseroots.server.ordering.OrderingJson.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ROSE_TEST_DB_URL", matches = "jdbc:postgresql:.+")
class OrderingPostgresTest {
    private static final String DATABASE = System.getenv("ROSE_TEST_DB_URL");
    private static final String USER = System.getenv("ROSE_TEST_DB_USERNAME");
    private static final String PASSWORD = System.getenv("ROSE_TEST_DB_PASSWORD");
    private static final String SCHEMA = "rr_ordering_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID TABLE = UUID.randomUUID();
    private static final String MANAGER_PASSWORD = token();
    private static final String STAFF_TOKEN = token();
    private static ServletWebServerApplicationContext server;
    private static JdbcClient db;
    private static HttpClient client;

    @TempDir
    static Path media;

    @BeforeAll
    static void start() {
        String url = DATABASE + (DATABASE.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        Flyway.configure().dataSource(url, USER, PASSWORD).schemas(SCHEMA).target("5").load().migrate();
        try (var connection = DriverManager.getConnection(url, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE restaurant_settings SET name = 'V5 historical branding' WHERE id = 1");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        server = (ServletWebServerApplicationContext) new SpringApplicationBuilder(ServerApplication.class).run(
                "--spring.profiles.active=catalog", "--server.address=127.0.0.1", "--server.port=0",
                "--ROSE_DB_URL=" + url, "--ROSE_DB_USERNAME=" + USER, "--ROSE_DB_PASSWORD=" + PASSWORD,
                "--spring.flyway.schemas=" + SCHEMA, "--rose.catalog.currency=KES",
                "--rose.catalog.image-directory=" + media,
                "--rose.admin.manager.username=ordering-manager-test",
                "--rose.admin.manager.password-hash=" + new BCryptPasswordEncoder(4).encode(MANAGER_PASSWORD));
        db = server.getBean(JdbcClient.class);
        db.sql("INSERT INTO ordering_restaurant (id) VALUES (:id)").param("id", RESTAURANT).update();
        db.sql("INSERT INTO ordering_table (id, restaurant_id, display_number) VALUES (:id, :restaurant, '07')")
                .param("id", TABLE).param("restaurant", RESTAURANT).update();
        credential(STAFF_TOKEN, null, "KITCHEN");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        try (var connection = DriverManager.getConnection(DATABASE, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void runningKitchenAdapterDiscoversCommittedOrdersAndReconcilesExternalPreparationChanges() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        UUID dish = dish();
        var kitchen = LoopbackKitchenClient.create(server.getWebServer().getPort(), STAFF_TOKEN);
        try {
            kitchen.startAutomaticRefresh();
            awaitKitchen(() -> kitchen.status() == KitchenSync.Status.CONNECTED);

            String orderId = submitNew(terminal, session, dish);
            var id = UUID.fromString(orderId);
            Supplier<Optional<Ticket>> matching = () ->
                    kitchen.activeTickets().stream().filter(order -> order.server().id().equals(id)).findFirst();
            awaitKitchen(() -> matching.get().isPresent());
            Ticket ticket = matching.get().orElseThrow();
            assertEquals(session, ticket.server().sessionId().toString());
            String itemId = ticket.items().getFirst().server().id().toString();
            assertEquals(Ticket.Status.RECEIVED, ticket.status());

            patch(itemId, "PREPARING", 1, 200);
            awaitKitchen(() -> matching.get().orElseThrow().status() == Ticket.Status.PREPARING);
            Ticket preparing = matching.get().orElseThrow();
            assertEquals("PREPARING", get(terminal, "/orders/" + orderId, 200).path("status").asText());
            assertEquals(2, preparing.items().getFirst().server().version());

            kitchen.advanceItem(preparing.id(), preparing.items().getFirst());
            awaitKitchen(() -> matching.get().orElseThrow().status() == Ticket.Status.READY);
            JsonNode customer = get(terminal, "/orders/" + orderId, 200);
            Ticket ready = matching.get().orElseThrow();
            assertEquals("READY", customer.path("status").asText());
            assertEquals(ready.server().version(), customer.path("version").asLong());
            assertEquals(itemId, customer.at("/lines/0/id").asText());
            assertEquals(3, ready.items().getFirst().server().version());
            assertEquals("READY", ticket(orderId).at("/order/status").asText());
            assertEquals(1L, db.sql("SELECT count(*) FROM accepted_order_item WHERE id = CAST(:id AS uuid)")
                    .param("id", itemId).query(Long.class).single());
        } finally {
            kitchen.stop();
        }
    }

    private static void awaitKitchen(BooleanSupplier completed) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (!completed.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(completed.getAsBoolean(), "Kitchen did not observe the persisted change");
    }

    @Test
    void upgradesV5AndPersistsOneOrderThroughReadyWithImmutablePriceAndReplay() throws Exception {
        assertEquals("V5 historical branding", db.sql("SELECT name FROM restaurant_settings WHERE id = 1")
                .query(String.class).single());
        String terminal = terminal(true);
        UUID dish = dish();
        assertEquals("07", get(terminal, "/device", 200).at("/table/displayNumber").asText());
        String session = session(terminal, "DINE_IN", 201);
        assertEquals(session, session(terminal, "DINE_IN", 200));
        assertEquals(session, get(terminal, "/sessions/" + session, 200).path("id").asText());
        problem(send(terminal, "POST", "/sessions", object().put("mode", "TAKEAWAY"), null),
                409, "SESSION_MODE_CONFLICT");

        ObjectNode draft = draft(session, dish);
        String group = legacyId(dish + "/group/", "sweetener");
        ((ObjectNode) draft.withArray("lines").get(0)).withArray("selections").addObject().put("groupId", group)
                .putArray("optionIds").add(legacyId(group + "/option/", "honey"));
        var quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        assertEquals(22025, quote.at("/lines/0/extendedMinor").asLong());
        ObjectNode body = submitBody(draft, quote);
        UUID key = UUID.randomUUID();
        JsonNode accepted = json(send(terminal, "POST", "/orders", body, key), 201);
        String order = accepted.path("id").asText();
        String item = accepted.at("/lines/0/id").asText();
        assertEquals("RECEIVED", accepted.path("status").asText());
        assertEquals(1L, db.sql("SELECT count(*) FROM accepted_order WHERE session_id = CAST(:id AS uuid)")
                .param("id", session).query(Long.class).single());
        assertEquals("Honey", db.sql("""
                SELECT pricing #>> '{options,0,name}' FROM accepted_order_item WHERE id = CAST(:id AS uuid)
                """).param("id", item).query(String.class).single());
        assertEquals(22025L, db.sql("""
                SELECT (pricing ->> 'extendedMinor')::bigint FROM accepted_order_item WHERE id = CAST(:id AS uuid)
                """).param("id", item).query(Long.class).single());

        JsonNode ticket = ticket(order);
        assertEquals("Table 07", ticket.path("serviceDestination").asText());
        assertEquals(item, ticket.at("/order/lines/0/id").asText());
        assertEquals("PREPARING", patch(item, "PREPARING", 1, 200).at("/order/status").asText());
        assertEquals("PREPARING", get(terminal, "/orders/" + order, 200).at("/lines/0/preparationStatus").asText());
        problem(send(STAFF_TOKEN, "PATCH", itemPath(item), transition("READY", 1), null), 409, "VERSION_CONFLICT");
        assertEquals("READY", patch(item, "READY", 2, 200).at("/order/status").asText());
        problem(send(STAFF_TOKEN, "PATCH", itemPath(item), transition("SERVED", 3), null), 409, "INVALID_TRANSITION");
        assertEquals("READY", get(terminal, "/orders/" + order, 200).path("status").asText());
        assertEquals("READY", ticket(order).at("/order/status").asText());
        assertEquals("READY", get(terminal, "/sessions/" + session + "/orders", 200).at("/orders/0/status").asText());
        assertEquals(22025L, get(terminal, "/sessions/" + session + "/bill", 200).at("/totals/totalMinor").asLong());
        assertEquals(3L, db.sql("SELECT count(*) FROM ordering_outbox WHERE order_id = CAST(:id AS uuid)")
                .param("id", order).query(Long.class).single());

        db.sql("UPDATE ordering_quote SET expires_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
                .param("id", body.path("quoteId").asText()).update();
        db.sql("UPDATE catalog_item SET price = 999.99 WHERE id = :id").param("id", dish).update();
        var replay = json(send(terminal, "POST", "/orders", body, key), 200);
        assertEquals(canonical(accepted).toString(), canonical(replay).toString());
        assertEquals(22025L, get(terminal, "/orders/" + order, 200).at("/totals/totalMinor").asLong());
        body.withArray("lines").get(0).withObject("/allergy").put("kind", "UNREVIEWED");
        problem(send(terminal, "POST", "/orders", body, key), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(1L, db.sql("SELECT count(*) FROM accepted_order WHERE session_id = CAST(:id AS uuid)")
                .param("id", session).query(Long.class).single());
    }

    @Test
    void enforcesBearerScopesTerminalOwnershipAndAdminCookieIsolation() throws Exception {
        String terminal = terminal(false);
        problem(send(terminal, "POST", "/sessions", object().put("mode", "DINE_IN"), null), 409, "DEVICE_UNASSIGNED");
        String session = session(terminal, "TAKEAWAY", 201);
        assertTrue(get(terminal, "/sessions/" + session, 200).hasNonNull("pickupReference"));
        problem(send(terminal(true), "GET", "/sessions/" + session, null, null), 404, "NOT_FOUND");
        problem(send(terminal, "GET", "/kitchen/orders", null, null), 403, "FORBIDDEN");
        problem(send(STAFF_TOKEN, "GET", "/device", null, null), 403, "FORBIDDEN");
        problem(send(null, "GET", "/device", null, null), 401, "UNAUTHENTICATED");
        problem(send(token(), "GET", "/device", null, null), 401, "UNAUTHENTICATED");
        assertEquals(401, send(STAFF_TOKEN, "GET", "/admin/dashboard", null, null).statusCode());
        try (var manager = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString());
            String form = "username=ordering-manager-test&password="
                    + URLEncoder.encode(MANAGER_PASSWORD, StandardCharsets.UTF_8);
            var login = request("/admin/login", null).header("X-CSRF-TOKEN", csrf.body())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            assertEquals(204, manager.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, manager.send(request("/admin/dashboard", null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            problem(manager.send(request("/kitchen/orders", null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "UNAUTHENTICATED");
        }
        db.sql("UPDATE ordering_credential SET revoked_at = now() WHERE token_sha256 = :hash")
                .param("hash", digest(terminal)).update();
        problem(send(terminal, "GET", "/device", null, null), 401, "UNAUTHENTICATED");
    }

    @Test
    void allergyDisclosureHoldsPreparationAndPriceChangesRequireNewQuote() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        UUID dish = dish();
        ObjectNode draft = draft(session, dish);
        draft.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("MILK");
        var quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        db.sql("UPDATE catalog_item SET price = 211.25 WHERE id = :id").param("id", dish).update();
        problem(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 409, "PRICE_CHANGED");
        quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        var accepted = json(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 201);
        String item = accepted.at("/lines/0/id").asText();
        assertEquals("PENDING_ALLERGY_REVIEW", accepted.at("/lines/0/allergyReviewStatus").asText());
        assertEquals("MILK", accepted.at("/lines/0/allergyRisk/ingredientMatches/0").asText());
        assertTrue(accepted.at("/lines/0/allergyRisk/unresolved").asBoolean());
        assertEquals("RECEIVED", ticket(accepted.path("id").asText()).at("/order/status").asText());
        problem(send(STAFF_TOKEN, "PATCH", itemPath(item), transition("PREPARING", 1), null), 409, "REVIEW_REQUIRED");
        problem(send(STAFF_TOKEN, "PATCH", itemPath(item), transition("READY", 1), null), 409, "REVIEW_REQUIRED");
        assertEquals("RECEIVED", get(terminal, "/orders/" + accepted.path("id").asText(), 200)
                .at("/lines/0/preparationStatus").asText());
    }

    @Test
    void concurrentReplayCommitsOneOrderAndCursorsBoundThePages() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        UUID dish = dish();
        ObjectNode draft = draft(session, dish);
        ObjectNode body = submitBody(draft, json(send(terminal, "POST", "/order-quotes", draft, null), 200));
        UUID key = UUID.randomUUID();
        var first = client.sendAsync(http(terminal, "POST", "/orders", body, key), HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(http(terminal, "POST", "/orders", body, key), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        var replies = java.util.List.of(first.join(), second.join());
        assertEquals(1, replies.stream().filter(reply -> reply.statusCode() == 201).count());
        var other = replies.stream().filter(reply -> reply.statusCode() != 201).findFirst().orElseThrow();
        if (other.statusCode() != 200) {
            problem(other, 409, "IDEMPOTENCY_IN_PROGRESS");
        }
        json(send(terminal, "POST", "/orders", body, key), 200);
        assertEquals(1L, db.sql("SELECT count(*) FROM accepted_order WHERE session_id = CAST(:id AS uuid)")
                .param("id", session).query(Long.class).single());

        String secondOrder = submitNew(terminal, session, dish);
        String history = "/sessions/" + session + "/orders?limit=1";
        var page = get(terminal, history, 200);
        assertTrue(page.hasNonNull("nextCursor"));
        String thirdOrder = submitNew(terminal, session, dish);
        var finalPage = get(terminal, history + "&cursor=" + page.path("nextCursor").asText(), 200);
        assertEquals(secondOrder, finalPage.at("/orders/0/id").asText());
        assertTrue(finalPage.path("nextCursor").isNull());
        assertEquals(3, get(terminal, "/sessions/" + session + "/bill", 200).path("orders").size());
        var kitchenIds = new HashSet<String>();
        String cursor = null;
        do {
            var kitchen = get(STAFF_TOKEN, "/kitchen/orders?limit=1" + (cursor == null ? "" : "&cursor=" + cursor), 200);
            assertTrue(kitchenIds.add(kitchen.at("/tickets/0/order/id").asText()));
            cursor = kitchen.path("nextCursor").isNull() ? null : kitchen.path("nextCursor").asText();
        } while (cursor != null);
        assertTrue(kitchenIds.containsAll(java.util.List.of(secondOrder, thirdOrder)));
        problem(send(STAFF_TOKEN, "GET", "/kitchen/orders?cursor=" + page.path("nextCursor").asText(), null, null),
                400, "BAD_REQUEST");
    }

    private static UUID dish() {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO catalog_item (id, category_id, name, description, cuisine, price, serving_size, ingredients,
                    allergens, dietary_tags, prep_minutes, kitchen_station, availability, archived,
                    customization_groups, display_order)
                VALUES (:id, (SELECT id FROM catalog_category WHERE name = 'Drinks'), 'Test tea', 'A fixture dish',
                    'INTERNATIONAL', 210.25, '250 ml', '["Tea","Milk"]', '["MILK"]', '[]', 10, 'Drinks',
                    'AVAILABLE', false, '[{"id":"sweetener","name":"Sweetener","selection":"EXTRA","minimum":0,
                    "maximum":1,"choices":[{"id":"honey","name":"Honey","priceAdjustment":10}]}]', 0)
                """).param("id", id).update();
        return id;
    }

    private static String terminal(boolean assigned) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO ordering_terminal (id, restaurant_id, table_id) VALUES (:id, :restaurant, :table)")
                .param("id", id).param("restaurant", RESTAURANT).param("table", assigned ? TABLE : null).update();
        String token = token();
        credential(token, id, "TERMINAL");
        return token;
    }

    private static void credential(String token, UUID terminal, String scope) {
        db.sql("""
                INSERT INTO ordering_credential (id, restaurant_id, terminal_id, scope, token_sha256, expires_at)
                VALUES (:id, :restaurant, :terminal, :scope, :hash, now() + interval '1 hour')
                """).param("id", UUID.randomUUID()).param("restaurant", RESTAURANT).param("terminal", terminal)
                .param("scope", scope).param("hash", digest(token)).update();
    }

    private static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String legacyId(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String session(String terminal, String mode, int status) throws Exception {
        return json(send(terminal, "POST", "/sessions", object().put("mode", mode), null), status).path("id").asText();
    }

    private static ObjectNode draft(String session, UUID dish) {
        var draft = object().put("sessionId", session);
        draft.set("visitAllergy", none());
        var line = draft.putArray("lines").addObject().put("clientLineId", UUID.randomUUID().toString())
                .put("menuItemId", dish.toString()).put("quantity", 1).put("preparationInstructions", "");
        line.set("allergy", none());
        line.putArray("selections");
        return draft;
    }

    private static ObjectNode none() { return object().put("kind", "NONE").put("other", "").set("allergens", array()); }

    private static ObjectNode submitBody(ObjectNode draft, JsonNode quote) {
        return draft.deepCopy().put("quoteId", quote.path("quoteId").asText()).put("allergyReviewed", true);
    }

    private static String submitNew(String terminal, String session, UUID dish) throws Exception {
        ObjectNode draft = draft(session, dish);
        JsonNode quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        return json(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 201)
                .path("id").asText();
    }

    private static JsonNode ticket(String order) throws Exception {
        for (JsonNode ticket : get(STAFF_TOKEN, "/kitchen/orders", 200).path("tickets")) {
            if (ticket.at("/order/id").asText().equals(order)) {
                return ticket;
            }
        }
        return fail("Expected the persisted order in the active Kitchen queue");
    }

    private static String itemPath(String id) { return "/kitchen/items/" + id + "/status"; }

    private static ObjectNode transition(String state, int version) {
        return object().put("status", state).put("expectedVersion", version);
    }

    private static JsonNode patch(String item, String state, int version, int status) throws Exception {
        return json(send(STAFF_TOKEN, "PATCH", itemPath(item), transition(state, version), null), status);
    }

    private static JsonNode get(String token, String path, int status) throws Exception {
        return json(send(token, "GET", path, null, null), status);
    }

    private static HttpResponse<String> send(String token, String method, String path, JsonNode node, UUID key)
            throws Exception {
        return client.send(http(token, method, path, node, key), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest http(String token, String method, String path, JsonNode node, UUID key) {
        var builder = request(path, token);
        if (key != null) {
            builder.header("Idempotency-Key", key.toString());
        }
        return node == null ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
                : builder.header("Content-Type", "application/json").method(method,
                        HttpRequest.BodyPublishers.ofString(node.toString())).build();
    }

    private static HttpRequest.Builder request(String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getWebServer().getPort()
                + "/api/v1" + path)).timeout(Duration.ofSeconds(15));
        return token == null ? builder : builder.header("Authorization", "Bearer " + token);
    }

    private static JsonNode json(HttpResponse<String> reply, int status) {
        assertEquals(status, reply.statusCode(), reply::body);
        return parse(reply.body());
    }

    private static void problem(HttpResponse<String> reply, int status, String code) {
        JsonNode body = json(reply, status);
        assertTrue(reply.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertEquals(code, body.path("code").asText());
        assertEquals(status, body.path("status").asInt());
        assertFalse(body.path("traceId").asText().isBlank());
    }
}
