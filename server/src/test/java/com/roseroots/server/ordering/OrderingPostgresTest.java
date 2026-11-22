package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.service.KitchenSync;
import com.roseroots.kitchen.service.http.LoopbackKitchenClient;
import com.roseroots.kitchen.screens.ConnectedAssistanceView;
import com.roseroots.kitchen.screens.ConnectedReviewsView;
import com.roseroots.kitchen.screens.OrderDetails;
import com.roseroots.server.ServerApplication;
import com.roseroots.admin.ManagerReviewsFixture;
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
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.Window;

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
    private static final String CHEF_TOKEN = token();
    private static ServletWebServerApplicationContext server;
    private static JdbcClient db;
    private static HttpClient client;
    private static boolean fxStarted;
    private static OrderingTls tls;

    @TempDir
    static Path media;

    @BeforeAll
    static void start() throws Exception {
        String url = DATABASE + (DATABASE.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        Flyway.configure().dataSource(url, USER, PASSWORD).schemas(SCHEMA).target("5").load().migrate();
        try (var connection = DriverManager.getConnection(url, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE restaurant_settings SET name = 'V5 historical branding' WHERE id = 1");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        tls = OrderingTls.create(media);
        server = (ServletWebServerApplicationContext) new SpringApplicationBuilder(ServerApplication.class).run(
                "--spring.profiles.active=catalog,lan", "--server.address=127.0.0.1", "--server.port=0",
                "--ROSE_SERVER_KEY_STORE=" + tls.store().toUri(), "--ROSE_SERVER_KEY_STORE_PASSWORD=" + tls.password(),
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
        credential(CHEF_TOKEN, null, "KITCHEN");
        db.sql("UPDATE ordering_credential SET allergy_reviewer = true WHERE token_sha256 = :hash")
                .param("hash", digest(CHEF_TOKEN)).update();
        client = HttpClient.newBuilder().sslContext(tls.trust()).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() throws Exception {
        if (fxStarted) Platform.exit();
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
    void ordinaryItemsInAMixedOrderAdvanceWhileAnotherItemIsHeld() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        var mixed = draft(session, dish());
        ((ObjectNode) mixed.withArray("lines").get(0)).withObject("/allergy")
                .put("kind", "DISCLOSED").putArray("allergens").add("SOY");
        mixed.withArray("lines").add(draft(session, dish()).at("/lines/0"));
        var quote = json(send(terminal, "POST", "/order-quotes", mixed, null), 200);
        var accepted = json(send(terminal, "POST", "/orders", submitBody(mixed, quote), UUID.randomUUID()), 201);
        String held = accepted.at("/lines/0/id").asText();
        String ordinary = accepted.at("/lines/1/id").asText();
        assertEquals("PENDING_ALLERGY_REVIEW", accepted.at("/lines/0/allergyReviewStatus").asText());
        assertEquals("NOT_REQUIRED", accepted.at("/lines/1/allergyReviewStatus").asText());
        patch(ordinary, "PREPARING", 1, 200);
        assertEquals("PREPARING", patch(ordinary, "READY", 2, 200).at("/order/status").asText());
        problem(send(STAFF_TOKEN, "PATCH", itemPath(held), transition("PREPARING", 1), null), 409, "REVIEW_REQUIRED");
        assertEquals("READY", json(review(CHEF_TOKEN, held, "DECLINE", 1), 200).at("/order/status").asText());
    }

    @Test
    void migrationFromV8RetainsPreviouslyPersistedOrdersAndAssistance() throws Exception {
        String schema = "rr_v8_" + UUID.randomUUID().toString().replace("-", "");
        String url = DATABASE + (DATABASE.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try {
            Flyway.configure().dataSource(url, USER, PASSWORD).schemas(schema).target("8").load().migrate();
            try (var connection = DriverManager.getConnection(url, USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO ordering_restaurant(id) VALUES ('10000000-0000-0000-0000-000000000001');
                        INSERT INTO ordering_terminal(id,restaurant_id)
                        VALUES ('10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001');
                        INSERT INTO dining_session(id,restaurant_id,terminal_id,mode,pickup_reference,created_at,expires_at,status)
                        VALUES ('10000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000001',
                            '10000000-0000-0000-0000-000000000002','TAKEAWAY','V8 pickup',now(),now()+interval '1 hour','OPEN');
                        INSERT INTO ordering_quote(id,session_id,expires_at,draft,pricing)
                        VALUES ('10000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000003',
                            now()+interval '1 minute','{}','{}');
                        INSERT INTO accepted_order(id,session_id,quote_id,accepted_at,status,visit_allergy,subtotal_minor,payable_minor,delay_at)
                        VALUES ('10000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000003',
                            '10000000-0000-0000-0000-000000000004',now(),'READY','{}',300,100,now());
                        INSERT INTO assistance_request(id,session_id,submission_key,input_sha256,type,note,status,version,submitted_at,updated_at)
                        VALUES ('10000000-0000-0000-0000-000000000006','10000000-0000-0000-0000-000000000003',
                            '10000000-0000-0000-0000-000000000007',decode(repeat('ab',32),'hex'),'OTHER','V8 request',
                            'ACKNOWLEDGED',2,now(),now());
                        """);
            }
            Flyway.configure().dataSource(url, USER, PASSWORD).schemas(schema).load().migrate();
            try (var connection = DriverManager.getConnection(url, USER, PASSWORD);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT o.status, o.subtotal_minor, o.payable_minor, a.note, a.status, a.version,
                                (SELECT count(*) FROM visit_review)
                         FROM accepted_order o JOIN assistance_request a ON a.session_id = o.session_id
                         """)) {
                assertTrue(result.next());
                assertEquals("READY", result.getString(1));
                assertEquals(300, result.getLong(2));
                assertEquals(100, result.getLong(3));
                assertEquals("V8 request", result.getString(4));
                assertEquals("ACKNOWLEDGED", result.getString(5));
                assertEquals(2, result.getLong(6));
                assertEquals(0, result.getLong(7));
                assertFalse(result.next());
            }
        } finally {
            try (var connection = DriverManager.getConnection(DATABASE, USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void reviewsRequireRealHandoffAndEnforceScopeReplayAndPrivateManagerAudit() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        String path = "/sessions/" + session;
        assertFalse(get(terminal, path + "/review", 200).path("eligible").asBoolean());
        String order = submitNew(terminal, session, dish());
        String item = get(terminal, "/orders/" + order, 200).at("/lines/0/id").asText();
        patch(item, "PREPARING", 1, 200);
        patch(item, "READY", 2, 200);
        var input = object().put("rating", 1).put("comment", "The tea arrived cold.");
        input.putArray("topics").add("TEMPERATURE").add("AMBIENCE");
        UUID key = UUID.randomUUID();
        problem(send(terminal, "POST", path + "/reviews", input, key), 422, "REVIEW_NOT_ELIGIBLE");
        json(send(STAFF_TOKEN, "POST", "/kitchen/orders/" + order + "/handoff", transition("SERVED", 3), null), 200);
        var eligible = get(terminal, path + "/review", 200);
        assertTrue(eligible.path("eligible").asBoolean());
        assertTrue(eligible.path("review").isNull(), "Skipping must not create a review or rating");
        problem(send(terminal(true), "GET", path + "/review", null, null), 404, "NOT_FOUND");
        problem(send(STAFF_TOKEN, "POST", path + "/reviews", input, key), 403, "FORBIDDEN");
        problem(send(terminal, "POST", path + "/reviews", object(), key), 422, "INVALID_SELECTION");
        var created = json(send(terminal, "POST", path + "/reviews", input, key), 201);
        String id = created.path("id").asText();
        assertEquals(created, json(send(terminal, "POST", path + "/reviews", input, key), 200));
        problem(send(terminal, "POST", path + "/reviews", input.deepCopy().put("rating", 2), key), 409, "IDEMPOTENCY_CONFLICT");
        problem(send(terminal, "POST", path + "/reviews", input, UUID.randomUUID()), 409, "REVIEW_ALREADY_EXISTS");
        assertEquals(created, get(terminal, path + "/review", 200).get("review"));
        var queue = get(STAFF_TOKEN, "/kitchen/reviews", 200).path("reviews");
        var row = java.util.stream.StreamSupport.stream(queue.spliterator(), false)
                .filter(entry -> entry.path("id").asText().equals(id)).findFirst().orElseThrow();
        assertEquals(1, row.path("rating").asInt());
        assertEquals(1, row.path("topics").size());
        assertFalse(row.has("sessionId"));
        assertFalse(row.has("internalResponse"));
        String route = "/admin/reviews/" + id + "/follow-up";
        var follow = object().put("followUp", "FOLLOWED_UP").put("internalResponse", "Private Manager note").put("expectedVersion", 1);
        assertEquals(403, send(STAFF_TOKEN, "PATCH", route, follow, null).statusCode());
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            var headers = request(route, null).header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(follow.toString()));
            assertEquals(403, manager.send(headers.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            var update = request(route, null).header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(follow.toString())).build();
            assertEquals(2, json(manager.send(update, HttpResponse.BodyHandlers.ofString()), 200).path("version").asLong());
            problem(manager.send(update, HttpResponse.BodyHandlers.ofString()), 409, "VERSION_CONFLICT");
            var page = json(manager.send(request("/admin/reviews?q=" + id, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals(1, page.at("/insights/negative").asLong());
            assertEquals("Private Manager note", page.at("/items/0/internalResponse").asText());
        }
        assertEquals(1L, db.sql("SELECT count(*) FROM review_follow_up_audit WHERE review_id = CAST(:id AS uuid)")
                .param("id", id).query(Long.class).single());
        assertFalse(get(STAFF_TOKEN, "/kitchen/reviews", 200).toString().contains("Private Manager note"));
    }

    @Test
    void javaFxKitchenShowsUnratedAndNegativeFeedbackFromRealHttps() throws Exception {
        startFx();
        for (int index = 0; index < 2; index++) {
            String terminal = terminal(true);
            String session = session(terminal, "DINE_IN", 201);
            String order = submitNew(terminal, session, dish());
            String item = get(terminal, "/orders/" + order, 200).at("/lines/0/id").asText();
            patch(item, "PREPARING", 1, 200);
            patch(item, "READY", 2, 200);
            json(send(STAFF_TOKEN, "POST", "/kitchen/orders/" + order + "/handoff", transition("SERVED", 3), null), 200);
            var draft = object().put("comment", index == 0 ? "Food was cold in the JavaFX test." : "Please review the preparation time.");
            draft.putArray("topics").add("PREPARATION_SPEED");
            if (index == 0) draft.put("rating", 1);
            json(send(terminal, "POST", "/sessions/" + session + "/reviews", draft, UUID.randomUUID()), 201);
        }
        var kitchen = LoopbackKitchenClient.create(server.getWebServer().getPort(), STAFF_TOKEN, tls.trust());
        var stage = fx(() -> {
            var root = new ConnectedReviewsView(kitchen);
            var window = new Stage();
            window.setScene(new Scene(root, 800, 550));
            window.show();
            root.refresh();
            return window;
        });
        try {
            var root = stage.getScene().getRoot();
            awaitKitchen(() -> uiText(root, "Food was cold in the JavaFX test.") && uiText(root, "Unrated"));
            assertTrue(uiText(root, "Average 1.0 / 5"));
        } finally {
            fx(() -> {
                ((ConnectedReviewsView) stage.getScene().getRoot()).stop();
                stage.close();
                return null;
            });
            kitchen.stop();
        }
    }

    @Test
    void javaFxManagerSavesFollowUpThroughTheProtectedHttpsRoute() throws Exception {
        startFx();
        String terminal = terminal(false);
        String session = session(terminal, "TAKEAWAY", 201);
        String order = submitNew(terminal, session, dish());
        String item = get(terminal, "/orders/" + order, 200).at("/lines/0/id").asText();
        patch(item, "PREPARING", 1, 200);
        patch(item, "READY", 2, 200);
        json(send(STAFF_TOKEN, "POST", "/kitchen/orders/" + order + "/handoff", transition("COLLECTED", 3), null), 200);
        var input = object().put("rating", 2).put("comment", "Follow-up fixture feedback");
        String id = json(send(terminal, "POST", "/sessions/" + session + "/reviews", input, UUID.randomUUID()), 201)
                .path("id").asText();
        var manager = new ManagerReviewsFixture("https://127.0.0.1:" + server.getWebServer().getPort(), tls.trust(),
                "ordering-manager-test", MANAGER_PASSWORD);
        var window = fx(() -> {
            var stage = new Stage();
            stage.setScene(new Scene(manager.view(), 900, 720));
            stage.show();
            return stage;
        });
        try {
            var root = window.getScene().getRoot();
            awaitKitchen(() -> uiText(root, "Connected to shared reviews."));
            fx(() -> {
                var table = (TableView<?>) root.lookup(".table-view");
                int index = -1;
                for (int i = 0; i < table.getItems().size(); i++) {
                    if (table.getItems().get(i).toString().contains(id)) index = i;
                }
                assertTrue(index >= 0);
                table.getSelectionModel().select(index);
                button(root, "View review").fire();
                ((ComboBox<?>) root.lookup(".combo-box")).getSelectionModel().select(1);
                ((TextArea) root.lookup(".text-area")).setText("Confirmed the preparation issue with the shift.");
                button(root, "Save follow-up").fire();
                return null;
            });
            awaitKitchen(() -> db.sql("""
                    SELECT count(*) FROM review_follow_up_audit
                    WHERE review_id = CAST(:id AS uuid) AND to_state = 'FOLLOWED_UP'
                    """).param("id", id).query(Long.class).single() == 1);
            assertTrue(uiText(root, "Management Follow-up"));
        } finally {
            fx(() -> { window.close(); return null; });
        }
    }

    private static void managerLogin(HttpClient manager) throws Exception {
        managerLogin(manager, "ordering-manager-test", MANAGER_PASSWORD);
    }

    private static void managerLogin(HttpClient manager, String username, String password) throws Exception {
        var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString());
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8);
        var login = request("/admin/login", null).header("X-CSRF-TOKEN", csrf.body())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        assertEquals(204, manager.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void managerProvisionsIndividualStaffAndDisablesExistingCredentialsAndSessions() throws Exception {
        try (var bootstrap = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build();
             var individual = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(bootstrap);
            var csrf = bootstrap.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            var chef = staffWrite(bootstrap, csrf, "POST", "", object().put("username", "integration-chef")
                    .put("role", "CHEF").put("allergyReviewer", true), 201);
            String token = chef.path("bearerToken").asText();
            assertEquals(43, token.length());
            get(token, "/kitchen/orders", 200);
            problem(send(token, "POST", "/kitchen/items/" + UUID.randomUUID() + "/allergy-review",
                    object().put("decision", "APPROVE").put("reason", "Verified").put("expectedVersion", 1), null),
                    404, "NOT_FOUND");
            String chefPath = "/" + chef.at("/account/id").asText();
            var off = object().put("enabled", false).put("allergyReviewer", false).put("expectedVersion", 1);
            assertEquals(2, staffWrite(bootstrap, csrf, "PATCH", chefPath, off, 200).path("version").asInt());
            staffWrite(bootstrap, csrf, "PATCH", chefPath, off, 409);
            problem(send(token, "GET", "/kitchen/orders", null, null), 401, "UNAUTHENTICATED");

            String password = UUID.randomUUID().toString();
            var manager = staffWrite(bootstrap, csrf, "POST", "", object().put("username", "integration-manager")
                    .put("role", "MANAGER").put("password", password), 201);
            managerLogin(individual, "integration-manager", password);
            assertEquals(200, individual.send(request("/admin/staff", null).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            staffWrite(bootstrap, csrf, "PATCH", "/" + manager.at("/account/id").asText(), off, 200);
            assertEquals(403, individual.send(request("/admin/staff", null).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(4L, db.sql("SELECT count(*) FROM staff_account_audit WHERE manager_username = 'ordering-manager-test'")
                    .query(Long.class).single());
        }
    }

    private static JsonNode staffWrite(HttpClient manager, String csrf, String method, String suffix,
                                       JsonNode body, int expected) throws Exception {
        var call = request("/admin/staff" + suffix, null).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        return json(manager.send(call.build(), HttpResponse.BodyHandlers.ofString()), expected);
    }

    @Test
    void explicitHandoffEnforcesScopeServiceModeReadinessAndOrderVersion() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        String order = submitNew(terminal, session, dish());
        String path = "/kitchen/orders/" + order + "/handoff";
        problem(send(terminal, "POST", path, transition("SERVED", 1), null), 403, "FORBIDDEN");
        problem(send(STAFF_TOKEN, "POST", path, transition("SERVED", 1), null), 409, "INVALID_TRANSITION");
        String item = get(terminal, "/orders/" + order, 200).at("/lines/0/id").asText();
        patch(item, "PREPARING", 1, 200);
        patch(item, "READY", 2, 200);
        problem(send(STAFF_TOKEN, "POST", path, transition("COLLECTED", 3), null), 409, "INVALID_TRANSITION");
        problem(send(STAFF_TOKEN, "POST", path, transition("SERVED", 2), null), 409, "VERSION_CONFLICT");
        JsonNode response = json(send(STAFF_TOKEN, "POST", path, transition("SERVED", 3), null), 200);
        assertEquals("SERVED", response.at("/order/lines/0/preparationStatus").asText());
        assertEquals("SERVED", get(terminal, "/orders/" + order, 200).path("status").asText());
        assertEquals(21025L, get(terminal, "/sessions/" + session + "/bill", 200).at("/totals/totalMinor").asLong());
        assertFalse(get(STAFF_TOKEN, "/kitchen/orders", 200).toString().contains(order));
        problem(send(terminal, "GET", "/kitchen/history", null, null), 403, "FORBIDDEN");
        var historyClient = LoopbackKitchenClient.create(server.getWebServer().getPort(), STAFF_TOKEN, tls.trust());
        try {
            var served = historyClient.loadHistory().get(5, java.util.concurrent.TimeUnit.SECONDS).stream()
                    .filter(ticket -> ticket.reference().equals(order)).findFirst().orElseThrow();
            assertEquals("SERVED", served.completion().status().name());
            assertNotNull(served.completion().at());
        } finally {
            historyClient.stop();
        }
        problem(send(STAFF_TOKEN, "POST", path, transition("SERVED", 3), null), 409, "VERSION_CONFLICT");
        assertEquals(1L, db.sql("SELECT count(*) FROM order_handoff WHERE order_id = CAST(:id AS uuid)")
                .param("id", order).query(Long.class).single());

        String pickupTerminal = terminal(false);
        String pickupSession = session(pickupTerminal, "TAKEAWAY", 201);
        String pickup = submitNew(pickupTerminal, pickupSession, dish());
        String pickupItem = get(pickupTerminal, "/orders/" + pickup, 200).at("/lines/0/id").asText();
        patch(pickupItem, "PREPARING", 1, 200);
        patch(pickupItem, "READY", 2, 200);
        assertEquals("COLLECTED", json(send(STAFF_TOKEN, "POST", "/kitchen/orders/" + pickup + "/handoff",
                transition("COLLECTED", 3), null), 200).at("/order/status").asText());
    }

    @Test
    void assistancePersistsAllTypesWithScopedHistoryAndIdempotentAcceptance() throws Exception {
        String terminal = terminal(true);
        String another = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        var types = java.util.List.of("WAITER", "DRINKING_WATER", "CUTLERY", "NAPKINS", "ORDER_HELP", "BILL", "OTHER");
        var key = UUID.randomUUID();
        ObjectNode other = object().put("sessionId", session).put("type", "OTHER").put("note", "Please help");
        for (String type : types.subList(0, 6)) {
            json(send(terminal, "POST", "/assistance", object().put("sessionId", session).put("type", type),
                    UUID.randomUUID()), 201);
        }
        JsonNode accepted = json(send(terminal, "POST", "/assistance", other, key), 201);
        String id = accepted.path("id").asText();
        assertEquals("SUBMITTED", accepted.path("status").asText());
        assertEquals("Table 07", accepted.path("serviceDestination").asText());
        assertEquals(accepted, json(send(terminal, "POST", "/assistance", other, key), 200));
        problem(send(terminal, "POST", "/assistance", other.deepCopy().put("note", "Changed"), key),
                409, "IDEMPOTENCY_CONFLICT");
        problem(send(terminal, "POST", "/assistance", other.deepCopy().put("type", "BILL"), UUID.randomUUID()),
                422, "INVALID_SELECTION");
        problem(send(another, "GET", "/sessions/" + session + "/assistance", null, null), 404, "NOT_FOUND");
        problem(send(another, "POST", "/assistance", other, UUID.randomUUID()), 404, "NOT_FOUND");
        problem(send(terminal, "GET", "/kitchen/assistance", null, null), 403, "FORBIDDEN");
        problem(send(terminal, "PATCH", "/kitchen/assistance/" + id, transition("ACKNOWLEDGED", 1), null),
                403, "FORBIDDEN");
        problem(send(STAFF_TOKEN, "PATCH", "/kitchen/assistance/" + id, transition("COMPLETED", 1), null),
                409, "INVALID_TRANSITION");
        var first = get(terminal, "/sessions/" + session + "/assistance?limit=2", 200);
        assertEquals(2, first.path("requests").size());
        assertEquals(5, get(terminal, "/sessions/" + session + "/assistance?cursor="
                + first.path("nextCursor").asText(), 200).path("requests").size());
        assertEquals(7L, db.sql("""
                SELECT count(*) FROM assistance_request WHERE session_id = CAST(:id AS uuid)
                """).param("id", session).query(Long.class).single());
        var ack = json(send(STAFF_TOKEN, "PATCH", "/kitchen/assistance/" + id, transition("ACKNOWLEDGED", 1), null), 200);
        assertEquals("ACKNOWLEDGED", ack.path("status").asText());
        problem(send(STAFF_TOKEN, "PATCH", "/kitchen/assistance/" + id, transition("IN_PROGRESS", 1), null),
                409, "VERSION_CONFLICT");
        assertEquals(accepted, json(send(terminal, "POST", "/assistance", other, key), 200));
        assertEquals("ACKNOWLEDGED", assistanceState(terminal, session, id));
        assertTrue(get(terminal, "/sessions/" + session + "/bill", 200).path("orders").isEmpty());
    }

    @Test
    void javaFxKitchenDiscoversPersistedAssistanceAndCustomerReadsEveryTransition() throws Exception {
        startFx();
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        var kitchen = LoopbackKitchenClient.create(server.getWebServer().getPort(), STAFF_TOKEN, tls.trust());
        var stage = fx(() -> {
            var window = new Stage();
            window.setScene(new Scene(new ConnectedAssistanceView(kitchen, count -> {}), 800, 560));
            window.show();
            return window;
        });
        try {
            Parent root = stage.getScene().getRoot();
            awaitKitchen(() -> uiText(root, "Assistance synchronizes automatically."));
            var input = object().put("sessionId", session).put("type", "BILL");
            var accepted = json(send(terminal, "POST", "/assistance", input, UUID.randomUUID()), 201);
            String id = accepted.path("id").asText();
            awaitKitchen(() -> uiText(root, id));
            click(root, "Acknowledge", id);
            awaitKitchen(() -> uiText(root, "Acknowledged") && statusIs(terminal, session, id, "ACKNOWLEDGED"));
            click(root, "Start", id);
            awaitKitchen(() -> statusIs(terminal, session, id, "IN_PROGRESS"));
            click(root, "Complete", id);
            awaitKitchen(() -> statusIs(terminal, session, id, "COMPLETED") && uiText(root, "Completed"));
            assertEquals(3L, db.sql("""
                    SELECT count(*) FROM assistance_transition a JOIN ordering_credential c ON c.id = a.staff_credential_id
                    WHERE a.request_id = CAST(:id AS uuid) AND c.token_sha256 = :hash
                    """).param("id", id).param("hash", digest(STAFF_TOKEN)).query(Long.class).single());
        } finally {
            fx(() -> {
                ((ConnectedAssistanceView) stage.getScene().getRoot()).stop();
                stage.close();
                return null;
            });
            kitchen.stop();
        }
    }

    @Test
    void javaFxChefReviewReleasesHoldAgainstRealBackend() throws Exception {
        startFx();
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        var draft = draft(session, dish());
        draft.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
        var quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        var persisted = json(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 201);
        String order = persisted.path("id").asText();
        var chef = LoopbackKitchenClient.create(server.getWebServer().getPort(), CHEF_TOKEN, tls.trust());
        chef.startAutomaticRefresh();
        Stage stage = null;
        Runnable remove = () -> {};
        try {
            Supplier<Optional<Ticket>> selected = () -> chef.activeTickets().stream()
                    .filter(ticket -> ticket.reference().equals(order)).findFirst();
            awaitKitchen(() -> selected.get().isPresent());
            var details = fx(() -> new OrderDetails(chef, Clock.systemUTC(), () -> {}));
            remove = chef.onOrdersChanged(() -> Platform.runLater(details::refresh));
            stage = fx(() -> {
                var window = new Stage();
                window.setScene(new Scene(details, 800, 560));
                details.open(selected.get().orElseThrow().id(), "Live Orders");
                window.show();
                return window;
            });
            awaitKitchen(() -> uiText(details, "PENDING_ALLERGY_REVIEW"));
            fx(() -> {
                Platform.runLater(() -> {
                    var dialog = Window.getWindows().stream().filter(Window::isShowing)
                            .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance)
                            .map(DialogPane.class::cast).findFirst().orElseThrow();
                    ((TextField) dialog.lookup(".text-field")).setText("Verified the preparation controls.");
                    ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                button(details, "Approve preparation").fire();
                return null;
            });
            awaitKitchen(() -> selected.get().orElseThrow().items().getFirst().actionable());
            assertTrue(fx(() -> button(details, "Start item").isVisible()));
            assertEquals("APPROVED_FOR_PREPARATION", get(terminal, "/orders/" + order, 200)
                    .at("/lines/0/allergyReviewStatus").asText());
        } finally {
            remove.run();
            chef.stop();
            if (stage != null) {
                Stage shown = stage;
                fx(() -> { shown.close(); return null; });
            }
        }
    }

    private static void startFx() throws Exception {
        if (fxStarted) return;
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fxStarted = true;
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static boolean uiText(Parent root, String text) {
        try {
            return fx(() -> {
                root.applyCss();
                root.layout();
                return root.lookupAll(".label").stream().map(Label.class::cast)
                        .anyMatch(label -> label.getText().contains(text));
            });
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void click(Parent root, String action, String id) throws Exception {
        awaitKitchen(() -> {
            try {
                return fx(() -> {
                    root.applyCss();
                    root.layout();
                    if (!(root.lookup("#assistance-action-" + id) instanceof Button button)
                            || !button.getText().equals(action) || button.isDisabled()) return false;
                    button.fire();
                    return true;
                });
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    private static Button button(Parent root, String text) {
        root.applyCss();
        root.layout();
        return root.lookupAll(".button").stream().map(Button.class::cast)
                .filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
    }

    private static String assistanceState(String terminal, String session, String id) throws Exception {
        var requests = get(terminal, "/sessions/" + session + "/assistance", 200).path("requests");
        for (JsonNode request : requests) if (request.path("id").asText().equals(id)) return request.path("status").asText();
        return "";
    }

    private static boolean statusIs(String terminal, String session, String id, String state) {
        try {
            return assistanceState(terminal, session, id).equals(state);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void runningKitchenAdapterDiscoversCommittedOrdersAndReconcilesExternalPreparationChanges() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        UUID dish = dish();
        var kitchen = LoopbackKitchenClient.create(server.getWebServer().getPort(), STAFF_TOKEN, tls.trust());
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
        while (System.nanoTime() < deadline) {
            if (completed.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Kitchen did not observe the persisted change");
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
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
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
        draft.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
        var quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        db.sql("UPDATE catalog_item SET price = 211.25 WHERE id = :id").param("id", dish).update();
        problem(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 409, "PRICE_CHANGED");
        quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        var accepted = json(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 201);
        String item = accepted.at("/lines/0/id").asText();
        assertEquals("PENDING_ALLERGY_REVIEW", accepted.at("/lines/0/allergyReviewStatus").asText());
        assertTrue(accepted.at("/lines/0/allergyRisk/ingredientMatches").isEmpty());
        assertTrue(accepted.at("/lines/0/allergyRisk/unresolved").asBoolean());
        assertEquals("RECEIVED", ticket(accepted.path("id").asText()).at("/order/status").asText());
        problem(send(STAFF_TOKEN, "PATCH", itemPath(item), transition("PREPARING", 1), null), 409, "REVIEW_REQUIRED");
        problem(send(STAFF_TOKEN, "PATCH", itemPath(item), transition("READY", 1), null), 409, "REVIEW_REQUIRED");
        assertEquals("RECEIVED", get(terminal, "/orders/" + accepted.path("id").asText(), 200)
                .at("/lines/0/preparationStatus").asText());
    }

    @Test
    void rejectsIngredientAndSelectedOptionAllergensOnQuoteAndRevalidatesAtSubmission() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        UUID dish = dish();
        ObjectNode draft = draft(session, dish);
        draft.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("MILK");
        var conflict = send(terminal, "POST", "/order-quotes", draft, null);
        problem(conflict, 422, "ALLERGEN_CONFLICT");
        assertEquals("/lines/0", parse(conflict.body()).at("/violations/0/pointer").asText());
        draft.withObject("/visitAllergy").putArray("allergens").add("EGGS");
        String group = legacyId(dish + "/group/", "sweetener");
        ((ObjectNode) draft.withArray("lines").get(0)).withArray("selections").addObject()
                .put("groupId", group).putArray("optionIds").add(legacyId(group + "/option/", "honey"));
        JsonNode quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        db.sql("""
                UPDATE catalog_item SET customization_groups =
                    jsonb_set(customization_groups, '{0,choices,0,allergens}', '["EGGS"]') WHERE id = :id
                """).param("id", dish).update();
        problem(send(terminal, "POST", "/order-quotes", draft, null), 422, "ALLERGEN_CONFLICT");
        problem(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 422, "ALLERGEN_CONFLICT");
    }

    @Test
    void chefApprovalAndDeclinePreserveSnapshotsAndAllowOtherItemsToFinish() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        UUID dish = dish();
        ObjectNode draft = draft(session, dish);
        draft.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
        draft.withArray("lines").add(draft(session, dish).at("/lines/0"));
        JsonNode quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        JsonNode accepted = json(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 201);
        String first = accepted.at("/lines/0/id").asText();
        String second = accepted.at("/lines/1/id").asText();
        String order = accepted.path("id").asText();
        problem(review(STAFF_TOKEN, first, "APPROVE", 1), 403, "FORBIDDEN");
        problem(send(STAFF_TOKEN, "PATCH", itemPath(first), transition("PREPARING", 1), null), 409, "REVIEW_REQUIRED");
        var approved = json(review(CHEF_TOKEN, first, "APPROVE", 1), 200);
        assertEquals("APPROVED_FOR_PREPARATION", approved.at("/order/lines/0/allergyReviewStatus").asText());
        assertFalse(approved.at("/order/lines/0/allergyRisk/unresolved").asBoolean());
        problem(review(CHEF_TOKEN, first, "APPROVE", 1), 409, "VERSION_CONFLICT");
        patch(first, "PREPARING", 2, 200);
        patch(first, "READY", 3, 200);
        var declined = json(review(CHEF_TOKEN, second, "DECLINE", 1), 200);
        assertEquals("READY", declined.at("/order/status").asText());
        assertFalse(declined.at("/order/lines/1/billable").asBoolean());
        assertEquals("CANCELLED", declined.at("/order/lines/1/preparationStatus").asText());
        assertEquals(21025, declined.at("/order/lines/1/pricing/extendedMinor").asLong());
        assertEquals(21025, get(terminal, "/sessions/" + session + "/bill", 200).at("/totals/totalMinor").asLong());
        assertEquals("DECLINED", get(terminal, "/orders/" + order, 200).at("/lines/1/allergyReviewStatus").asText());
        assertFalse(declined.toString().contains("Private fixture reason"));
        assertEquals(2L, db.sql("""
                SELECT count(*) FROM order_item_allergy_review r JOIN ordering_credential c ON c.id = r.reviewer_id
                WHERE c.token_sha256 = :hash AND r.reason = 'Private fixture reason'
                """).param("hash", digest(CHEF_TOKEN)).query(Long.class).single());
    }

    private static HttpResponse<String> review(String token, String item, String decision, int version) throws Exception {
        return send(token, "POST", "/kitchen/items/" + item + "/allergy-review",
                object().put("decision", decision).put("reason", "Private fixture reason").put("expectedVersion", version), null);
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
        var builder = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.getWebServer().getPort()
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
