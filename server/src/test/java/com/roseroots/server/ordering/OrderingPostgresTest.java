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
    private static final String CHEF_RAW = token();
    private static final String CHEF_PASSWORD = token();
    private static final String CHEF_TOKEN = "ks_" + token();
    private static final String PREP_TOKEN = "ks_" + token();
    private static final String HANDOFF_TOKEN = "ks_" + token();
    private static final UUID PREP_CHEF = UUID.randomUUID();
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
        db.sql("""
                INSERT INTO staff_account (id,username,staff_role,credential_id)
                SELECT :id,'regression-prep-chef','CHEF',id FROM ordering_credential WHERE token_sha256=:hash
                """).param("id", PREP_CHEF).param("hash", digest(STAFF_TOKEN)).update();
        db.sql("""
                INSERT INTO kitchen_staff_session (token_sha256,staff_id,entry_credential_id,expires_at)
                SELECT :session,:id,id,now()+interval '10 minutes' FROM ordering_credential WHERE token_sha256=:hash
                """).param("id", PREP_CHEF).param("hash", digest(STAFF_TOKEN)).param("session", digest(PREP_TOKEN)).update();
        String waiterCredential = token();
        credential(waiterCredential, null, "KITCHEN");
        UUID waiter = UUID.randomUUID();
        db.sql("""
                INSERT INTO staff_account (id,username,staff_role,credential_id)
                SELECT :id,'regression-waiter','WAITER',id FROM ordering_credential WHERE token_sha256=:hash
                """).param("id", waiter).param("hash", digest(waiterCredential)).update();
        db.sql("""
                INSERT INTO kitchen_staff_session (token_sha256,staff_id,entry_credential_id,expires_at)
                SELECT :session,:id,id,now()+interval '10 minutes' FROM ordering_credential WHERE token_sha256=:entry
                """).param("id", waiter).param("entry", digest(STAFF_TOKEN)).param("session", digest(HANDOFF_TOKEN)).update();
        credential(CHEF_RAW, null, "KITCHEN");
        db.sql("""
                INSERT INTO staff_account (id, username, staff_role, credential_id, kitchen_password_hash)
                SELECT :id,'regression-allergy-chef','CHEF',id,:password FROM ordering_credential WHERE token_sha256=:hash
                """).param("id", UUID.randomUUID()).param("hash", digest(CHEF_RAW))
                .param("password", new BCryptPasswordEncoder(4).encode(CHEF_PASSWORD)).update();
        db.sql("""
                INSERT INTO kitchen_staff_session (token_sha256,staff_id,entry_credential_id,expires_at)
                SELECT :session,s.id,s.credential_id,now()+interval '20 minutes' FROM staff_account s
                WHERE s.username='regression-allergy-chef'
                """).param("session", digest(CHEF_TOKEN)).update();
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
        problem(send(PREP_TOKEN, "PATCH", itemPath(held), transition("PREPARING", 1), null), 409, "REVIEW_REQUIRED");
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
        json(send(HANDOFF_TOKEN, "POST", "/kitchen/orders/" + order + "/handoff", transition("SERVED", 3), null), 200);
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
            json(send(HANDOFF_TOKEN, "POST", "/kitchen/orders/" + order + "/handoff", transition("SERVED", 3), null), 200);
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
        json(send(HANDOFF_TOKEN, "POST", "/kitchen/orders/" + order + "/handoff", transition("COLLECTED", 3), null), 200);
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
            String chefPassword = token();
            String chef = kitchenAccount(bootstrap, csrf, "integration-chef", "CHEF", chefPassword);
            String chefPath = "/" + chef;
            String workstation = issueWorkstation(bootstrap, csrf, "Lifecycle workstation").path("bearerToken").asText();
            String legacy = token();
            db.sql("UPDATE ordering_credential SET token_sha256=:hash WHERE id=(SELECT credential_id FROM staff_account WHERE id=CAST(:id AS uuid))")
                    .param("hash", digest(legacy)).param("id", chef).update();
            get(legacy, "/kitchen/orders", 200);
            var signIn = json(send(workstation, "POST", "/kitchen/staff-session",
                    object().put("username", "integration-chef").put("password", chefPassword), null), 201);
            assertTrue(signIn.path("allergyReviewer").asBoolean());
            String personal = signIn.path("sessionToken").asText();
            String terminal = terminal(true);
            String orderSession = session(terminal, "DINE_IN", 201);
            var held = draft(orderSession, dish());
            held.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
            var quote = json(send(terminal, "POST", "/order-quotes", held, null), 200);
            String item = json(send(terminal, "POST", "/orders", submitBody(held, quote), UUID.randomUUID()), 201)
                    .at("/lines/0/id").asText();
            problem(review(legacy, item, "APPROVE", 1), 403, "FORBIDDEN");
            problem(review(workstation, item, "APPROVE", 1), 403, "FORBIDDEN");
            json(review(personal, item, "APPROVE", 1), 200);

            var off = object().put("state", "DEACTIVATED").put("expectedVersion", 1);
            assertEquals(2, staffWrite(bootstrap, csrf, "PATCH", chefPath, off, 200).path("version").asInt());
            staffWrite(bootstrap, csrf, "PATCH", chefPath, off, 409);
            problem(review(personal, item, "APPROVE", 2), 401, "UNAUTHENTICATED");
            problem(send(legacy, "GET", "/kitchen/orders", null, null), 401, "UNAUTHENTICATED");
            assertEquals("ARCHIVED", staffWrite(bootstrap, csrf, "PATCH", chefPath,
                    object().put("state", "ARCHIVED").put("expectedVersion", 2), 200).path("state").asText());
            assertEquals("integration-chef", db.sql("""
                    SELECT s.username FROM order_item_allergy_review r JOIN staff_account s ON s.credential_id=r.reviewer_id
                    WHERE r.item_id=CAST(:item AS uuid) AND s.archived_at IS NOT NULL
                    """).param("item", item).query(String.class).single());
            staffWrite(bootstrap, csrf, "PATCH", chefPath, object().put("state", "ACTIVE").put("expectedVersion", 3)
                    .put("password", token()), 409);
            assertTrue(staffWrite(bootstrap, csrf, "PATCH", chefPath, object().put("state", "DEACTIVATED")
                    .put("expectedVersion", 3), 200).path("needsPasswordSetup").asBoolean());
            staffWrite(bootstrap, csrf, "PATCH", chefPath, object().put("state", "ACTIVE").put("expectedVersion", 4), 409);
            staffWrite(bootstrap, csrf, "PATCH", chefPath, object().put("state", "ACTIVE")
                    .put("expectedVersion", 4).put("password", chefPassword), 400);
            String newPassword = token();
            staffWrite(bootstrap, csrf, "PATCH", chefPath, object().put("state", "ACTIVE")
                    .put("expectedVersion", 4).put("password", newPassword), 200);
            problem(send(legacy, "GET", "/kitchen/orders", null, null), 401, "UNAUTHENTICATED");
            problem(send(personal, "GET", "/kitchen/orders", null, null), 401, "UNAUTHENTICATED");
            problem(send(workstation, "POST", "/kitchen/staff-session",
                    object().put("username", "integration-chef").put("password", chefPassword), null), 401, "UNAUTHENTICATED");
            get(kitchenSignIn(workstation, "integration-chef", newPassword), "/kitchen/orders", 200);
            assertEquals(java.util.List.of("ACTIVE>DEACTIVATED", "DEACTIVATED>ARCHIVED",
                            "ARCHIVED>DEACTIVATED", "DEACTIVATED>ACTIVE"),
                    db.sql("""
                            SELECT previous_state || '>' || new_state FROM staff_account_audit
                            WHERE account_id=CAST(:id AS uuid) AND previous_state IS NOT NULL
                                AND manager_username='ordering-manager-test' AND recorded_at IS NOT NULL ORDER BY version
                            """).param("id", chef).query(String.class).list());

            String password = token();
            String manager = kitchenAccount(bootstrap, csrf, "integration-manager", "MANAGER", password);
            managerLogin(individual, "integration-manager", password);
            String ownCsrf = individual.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            staffWrite(individual, ownCsrf, "PATCH", "/" + manager, off, 409);
            assertEquals(200, individual.send(request("/admin/staff", null).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            staffWrite(bootstrap, csrf, "PATCH", "/" + manager, off, 200);
            assertEquals(403, individual.send(request("/admin/staff", null).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            String replacement = token();
            staffWrite(bootstrap, csrf, "PATCH", "/" + manager, object().put("state", "ACTIVE")
                    .put("expectedVersion", 2).put("password", replacement), 200);
            assertEquals(403, individual.send(request("/admin/staff", null).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            try (var fresh = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
                managerLogin(fresh, "integration-manager", replacement);
                assertEquals(200, fresh.send(request("/admin/staff", null).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }

    private static JsonNode staffWrite(HttpClient manager, String csrf, String method, String suffix,
                                       JsonNode body, int expected) throws Exception {
        var call = request("/admin/staff" + suffix, null).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        return json(manager.send(call.build(), HttpResponse.BodyHandlers.ofString()), expected);
    }

    @Test
    void supervisorAndManagerAuditPriorityWithoutReleasingAllergyHolds() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        ObjectNode input = draft(session, dish());
        input.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
        var quote = json(send(terminal, "POST", "/order-quotes", input, null), 200);
        var accepted = json(send(terminal, "POST", "/orders", submitBody(input, quote), UUID.randomUUID()), 201);
        String order = accepted.path("id").asText();
        String item = accepted.at("/lines/0/id").asText();
        String path = "/kitchen/orders/" + order;
        var rush = object().put("priority", "RUSH").put("reason", "Service recovery").put("expectedVersion", 1);
        problem(send(CHEF_TOKEN, "POST", path + "/rush", rush, null), 403, "FORBIDDEN");
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            String password = token();
            kitchenAccount(manager, csrf, "priority-supervisor", "SUPERVISOR", password);
            String supervisor = kitchenSignIn(STAFF_TOKEN, "priority-supervisor", password);
            assertEquals("RUSH", json(send(supervisor, "POST", path + "/rush", rush, null), 200).path("priority").asText());
            problem(send(supervisor, "POST", path + "/rush", rush, null), 409, "VERSION_CONFLICT");
            problem(send(supervisor, "PATCH", "/kitchen/items/" + item + "/status", transition("PREPARING", 1), null),
                    403, "FORBIDDEN");
            problem(send(PREP_TOKEN, "PATCH", itemPath(item), transition("PREPARING", 1), null),
                    409, "REVIEW_REQUIRED");
            var delay = object().put("delayed", true).put("reason", "Station paused").put("expectedVersion", 2);
            assertTrue(json(send(supervisor, "POST", path + "/delayed", delay, null), 200).path("delayed").asBoolean());
            var remove = request("/admin/orders/" + order + "/delayed", null).header("X-CSRF-TOKEN", csrf)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                            delay.put("delayed", false).put("expectedVersion", 3).toString())).build();
            assertFalse(json(manager.send(remove, HttpResponse.BodyHandlers.ofString()), 200).path("delayed").asBoolean());
            assertEquals(3L, db.sql("SELECT count(*) FROM order_priority_audit WHERE order_id = CAST(:id AS uuid)")
                    .param("id", order).query(Long.class).single());
        }
    }

    @Test
    void managerConfiguresRealStationsAvailabilityAndImmutableOrderRouting() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        UUID menu = dish();
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            var chef = staffWrite(manager, csrf, "POST", "", object().put("username", "station-chef")
                    .put("role", "CHEF").put("password", token()), 201).at("/account/id").asText();
            var stations = json(manager.send(request("/admin/stations", null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals(3, stations.size());
            String station = stations.get(0).path("id").asText();
            var config = object().put("active", true).put("parallelSlots", 2)
                    .put("expectedVersion", stations.get(0).path("version").asLong());
            config.putArray("chefIds").add(chef);
            var assigned = configWrite(manager, csrf, "/stations/" + station, config, 200);
            assertEquals(0, assigned.path("qualifiedAvailableChefs").asInt());
            configWrite(manager, csrf, "/stations/" + station, config, 409);
            var availability = object().put("minutes", 60).put("expectedVersion", 1);
            assertFalse(configWrite(manager, csrf, "/chef-availability/" + chef, availability, 200).path("availableUntil").isNull());
            var live = json(manager.send(request("/admin/stations", null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals(1, live.get(0).path("qualifiedAvailableChefs").asInt());
            var profile = object().put("stationId", station).put("durationSeconds", 420).put("expectedVersion", 0);
            assertEquals(1, configWrite(manager, csrf, "/prep-times/" + menu, profile, 200).path("version").asLong());
            String order = submitNew(terminal, session, menu);
            String item = get(terminal, "/orders/" + order, 200).at("/lines/0/id").asText();
            assertEquals("BEVERAGES", ticket(order).path("stationCodes").path(item).asText());
            assertFalse(get(terminal, "/orders/" + order, 200).has("stationCodes"));
            profile.put("stationId", stations.get(1).path("id").asText()).put("durationSeconds", 600).put("expectedVersion", 1);
            assertEquals(2, configWrite(manager, csrf, "/prep-times/" + menu, profile, 200).path("version").asLong());
            assertEquals("BEVERAGES", ticket(order).path("stationCodes").path(item).asText());
            patch(item, "PREPARING", 1, 200);
            assertTrue(db.sql("""
                    SELECT planned_duration_seconds = 420 AND preparation_started_at IS NOT NULL
                    FROM accepted_order_item WHERE id = CAST(:id AS uuid)
                    """).param("id", item).query(Boolean.class).single());
        }
    }

    private static JsonNode configWrite(HttpClient manager, String csrf, String path, JsonNode body,
                                        int expected) throws Exception {
        var call = request("/admin" + path, null).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return json(manager.send(call, HttpResponse.BodyHandlers.ofString()), expected);
    }

    @Test
    void managerProvisionsAndDisablesWaiterWithNarrowPersonalHandoffPermission() throws Exception {
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            String csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            String password = token();
            String waiter = kitchenAccount(manager, csrf, "service-waiter", "WAITER", password);
            String workstation = issueWorkstation(manager, csrf, "Service pass").path("bearerToken").asText();
            String personal = kitchenSignIn(workstation, "service-waiter", password);
            String customer = terminal(true);
            String session = session(customer, "DINE_IN", 201);
            String order = submitNew(customer, session, dish());
            String path = "/kitchen/orders/" + order + "/handoff";
            String item = ticket(order).at("/order/lines/0/id").asText();
            problem(send(personal, "POST", path, transition("SERVED", 1), null), 409, "INVALID_TRANSITION");
            problem(send(personal, "PATCH", itemPath(item), transition("PREPARING", 1), null), 403, "FORBIDDEN");
            problem(send(personal, "POST", "/kitchen/items/" + item + "/ownership", object(), null), 403, "FORBIDDEN");
            problem(send(personal, "POST", "/kitchen/items/" + item + "/allergy-review", object(), null), 403, "FORBIDDEN");
            problem(send(personal, "POST", "/kitchen/orders/" + order + "/rush", object(), null), 403, "FORBIDDEN");
            patch(item, "PREPARING", 1, 200);
            patch(item, "READY", 2, 200);
            assertEquals("READY", get(customer, "/orders/" + order, 200).path("status").asText());
            assertFalse(get(customer, "/sessions/" + session + "/review", 200).path("eligible").asBoolean());
            assertTrue(get(workstation, "/kitchen/orders", 200).path("tickets").toString().contains(order));
            problem(send(workstation, "POST", path, transition("SERVED", 3), null), 403, "FORBIDDEN");
            problem(send(PREP_TOKEN, "POST", path, transition("SERVED", 3), null), 403, "FORBIDDEN");
            problem(send(STAFF_TOKEN, "POST", path, transition("SERVED", 3), null), 403, "FORBIDDEN");
            problem(send(personal, "POST", path, transition("SERVED", 2), null), 409, "VERSION_CONFLICT");
            db.sql("UPDATE accepted_order_item SET allergy_review_status='PENDING_ALLERGY_REVIEW' WHERE id=CAST(:id AS uuid)")
                    .param("id", item).update();
            problem(send(personal, "POST", path, transition("SERVED", 3), null), 409, "REVIEW_REQUIRED");
            db.sql("UPDATE accepted_order_item SET allergy_review_status='NOT_REQUIRED' WHERE id=CAST(:id AS uuid)")
                    .param("id", item).update();
            var result = json(send(personal, "POST", path, transition("SERVED", 3), null), 200);
            assertEquals(waiter, result.at("/handoff/actorStaffId").asText());
            assertEquals("service-waiter", result.at("/handoff/actor").asText());
            assertEquals("WAITER", result.at("/handoff/actorRole").asText());
            assertEquals("READY", result.at("/handoff/previousStatus").asText());
            assertEquals("SERVED", result.at("/handoff/status").asText());
            assertEquals(3, result.at("/handoff/previousVersion").asInt());
            assertEquals(4, result.at("/handoff/version").asInt());
            assertEquals(PREP_CHEF.toString(), result.path("itemChefs").path(item).path("id").asText());
            assertTrue(get(customer, "/sessions/" + session + "/review", 200).path("eligible").asBoolean());
            assertFalse(get(customer, "/orders/" + order, 200).has("handoff"));
            assertTrue(get(personal, "/kitchen/history", 200).path("tickets").toString().contains("service-waiter"));
            problem(send(personal, "POST", path, transition("SERVED", 4), null), 409, "INVALID_TRANSITION");
            staffWrite(manager, csrf, "PATCH", "/" + waiter, object().put("expectedVersion", 1).put("state", "ARCHIVED"), 200);
            problem(send(personal, "POST", path, transition("SERVED", 4), null), 401, "UNAUTHENTICATED");
            assertEquals(waiter, db.sql("SELECT actor_staff_id FROM order_handoff WHERE order_id=CAST(:id AS uuid)")
                    .param("id", order).query(UUID.class).single().toString());
        }
    }

    @Test
    void explicitHandoffEnforcesScopeServiceModeReadinessAndOrderVersion() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        String order = submitNew(terminal, session, dish());
        String path = "/kitchen/orders/" + order + "/handoff";
        problem(send(terminal, "POST", path, transition("SERVED", 1), null), 403, "FORBIDDEN");
        problem(send(HANDOFF_TOKEN, "POST", path, transition("SERVED", 1), null), 409, "INVALID_TRANSITION");
        String item = get(terminal, "/orders/" + order, 200).at("/lines/0/id").asText();
        patch(item, "PREPARING", 1, 200);
        patch(item, "READY", 2, 200);
        problem(send(HANDOFF_TOKEN, "POST", path, transition("COLLECTED", 3), null), 409, "INVALID_TRANSITION");
        problem(send(HANDOFF_TOKEN, "POST", path, transition("SERVED", 2), null), 409, "VERSION_CONFLICT");
        JsonNode response = json(send(HANDOFF_TOKEN, "POST", path, transition("SERVED", 3), null), 200);
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
        problem(send(HANDOFF_TOKEN, "POST", path, transition("SERVED", 3), null), 409, "VERSION_CONFLICT");
        assertEquals(1L, db.sql("SELECT count(*) FROM order_handoff WHERE order_id = CAST(:id AS uuid)")
                .param("id", order).query(Long.class).single());

        String pickupTerminal = terminal(false);
        String pickupSession = session(pickupTerminal, "TAKEAWAY", 201);
        String pickup = submitNew(pickupTerminal, pickupSession, dish());
        String pickupItem = get(pickupTerminal, "/orders/" + pickup, 200).at("/lines/0/id").asText();
        patch(pickupItem, "PREPARING", 1, 200);
        patch(pickupItem, "READY", 2, 200);
        assertEquals("COLLECTED", json(send(HANDOFF_TOKEN, "POST", "/kitchen/orders/" + pickup + "/handoff",
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
    void signedInChefsOwnIndividualItemsAndSupervisorReassignsWithHistory() throws Exception {
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            String csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            String workstation = issueWorkstation(manager, csrf, "Preparation test").path("bearerToken").asText();
            String password = token();
            String alice = kitchenAccount(manager, csrf, "prep-alice", "CHEF", password);
            String bob = kitchenAccount(manager, csrf, "prep-bob", "CHEF", password);
            String supervisor = kitchenAccount(manager, csrf, "prep-supervisor", "SUPERVISOR", password);
            UUID menu = dish();
            UUID station = db.sql("SELECT id FROM kitchen_station WHERE code='HOT'").query(UUID.class).single();
            db.sql("""
                    INSERT INTO kitchen_prep_profile(menu_item_id,station_id,duration_seconds)
                    VALUES (:menu,:station,60)
                    """).param("menu", menu).param("station", station).update();
            db.sql("""
                    INSERT INTO kitchen_station_chef(station_id,chef_id)
                    SELECT :station,id FROM staff_account WHERE username IN ('prep-alice','prep-bob')
                    """).param("station", station).update();
            db.sql("UPDATE staff_account SET available_until=now()+interval '5 minutes' WHERE username='prep-alice'").update();

            var terminal = terminal(true);
            var session = session(terminal, "DINE_IN", 201);
            var draft = draft(session, menu);
            draft.withArray("lines").add(draft(session, menu).at("/lines/0"));
            var quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
            var order = json(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 201);
            String first = order.at("/lines/0/id").asText();
            String second = order.at("/lines/1/id").asText();
            String one = "/kitchen/items/" + first + "/ownership";
            String two = "/kitchen/items/" + second + "/ownership";
            var claim = object().put("action", "CLAIMED").put("expectedVersion", 1);
            problem(send(workstation, "POST", one, claim, null), 403, "FORBIDDEN");
            problem(send(workstation, "POST", "/kitchen/staff-session",
                    object().put("username", "prep-alice").put("password", token()), null), 401, "UNAUTHENTICATED");
            String aliceToken = kitchenSignIn(workstation, "prep-alice", password);
            String bobToken = kitchenSignIn(workstation, "prep-bob", password);
            String supervisorToken = kitchenSignIn(workstation, "prep-supervisor", password);
            problem(send(bobToken, "POST", two, claim, null), 409, "CHEF_NOT_AVAILABLE");
            db.sql("UPDATE staff_account SET available_until=now()+interval '5 minutes' WHERE username='prep-bob'").update();
            var claims = java.util.List.of(client.sendAsync(http(aliceToken, "POST", one, claim, null),
                    HttpResponse.BodyHandlers.ofString()), client.sendAsync(http(bobToken, "POST", one, claim, null),
                    HttpResponse.BodyHandlers.ofString()));
            var firstReply = claims.getFirst().get();
            var secondReply = claims.getLast().get();
            boolean aliceWon = firstReply.statusCode() == 200;
            json(aliceWon ? firstReply : secondReply, 200);
            problem(aliceWon ? secondReply : firstReply, 409, "VERSION_CONFLICT");
            String owner = aliceWon ? aliceToken : bobToken;
            String other = aliceWon ? bobToken : aliceToken;
            String otherId = aliceWon ? bob : alice;
            json(send(other, "POST", two, claim, null), 200);
            problem(send(other, "PATCH", itemPath(first), transition("PREPARING", 2), null), 403, "FORBIDDEN");
            json(send(owner, "PATCH", itemPath(first), transition("PREPARING", 2), null), 200);
            var reassign = object().put("action", "REASSIGNED").put("expectedVersion", 3)
                    .put("chefId", otherId).put("reason", "Shift coverage");
            problem(send(workstation, "POST", one, reassign, null), 403, "FORBIDDEN");
            json(send(supervisorToken, "POST", one, reassign, null), 200);
            problem(send(owner, "PATCH", itemPath(first), transition("READY", 4), null), 403, "FORBIDDEN");
            json(send(other, "PATCH", itemPath(first), transition("READY", 4), null), 200);
            var events = get(other, "/kitchen/items/" + first + "/preparation-history", 200).path("events");
            assertEquals(java.util.List.of("CLAIMED", "STARTED", "REASSIGNED", "READY"),
                    java.util.stream.StreamSupport.stream(events.spliterator(), false).map(event -> event.path("action").asText()).toList());
            assertEquals("Shift coverage", events.get(2).path("reason").asText());
            assertEquals("prep-supervisor", events.get(2).path("actor").asText());
            assertNotEquals(events.get(0).path("actor"), events.get(3).path("actor"));
            var held = draft(session, menu);
            held.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
            var heldQuote = json(send(terminal, "POST", "/order-quotes", held, null), 200);
            String heldItem = json(send(terminal, "POST", "/orders", submitBody(held, heldQuote), UUID.randomUUID()), 201)
                    .at("/lines/0/id").asText();
            json(send(other, "POST", "/kitchen/items/" + heldItem + "/ownership", claim, null), 200);
            problem(send(other, "PATCH", itemPath(heldItem), transition("PREPARING", 2), null), 409, "REVIEW_REQUIRED");
            db.sql("UPDATE ordering_credential SET revoked_at=now() WHERE id=(SELECT credential_id FROM staff_account WHERE id=CAST(:id AS uuid))")
                    .param("id", otherId).update();
            problem(send(other, "PATCH", itemPath(second), transition("PREPARING", 2), null), 401, "UNAUTHENTICATED");
            assertEquals(204, send(supervisorToken, "DELETE", "/kitchen/staff-session", null, null).statusCode());
            problem(send(supervisorToken, "GET", "/kitchen/orders", null, null), 401, "UNAUTHENTICATED");
            db.sql("UPDATE staff_account SET available_until=NULL WHERE username IN ('prep-alice','prep-bob')").update();
        }
    }

    private static String kitchenAccount(HttpClient manager, String csrf, String username, String role, String password)
            throws Exception {
        var created = staffWrite(manager, csrf, "POST", "", object().put("username", username)
                .put("role", role).put("password", password), 201);
        assertFalse(created.has("bearerToken"));
        return created.at("/account/id").asText();
    }

    private static String kitchenSignIn(String workstation, String username, String password) throws Exception {
        return json(send(workstation, "POST", "/kitchen/staff-session",
                object().put("username", username).put("password", password), null), 201).path("sessionToken").asText();
    }

    @Test
    void workstationCredentialsAreRestrictedAndRevocable() throws Exception {
        String path = "/admin/kitchen-workstations";
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        String order = submitNew(terminal, session, dish());
        String item = ticket(order).at("/order/lines/0/id").asText();
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertEquals(403, manager.send(request(path, null).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"label\":\"Missing CSRF\"}")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            var issued = issueWorkstation(manager, csrf, "Shared test display");
            String token = issued.path("bearerToken").asText();
            UUID id = UUID.fromString(issued.at("/workstation/id").asText());
            assertTrue(token.matches("ws_[A-Za-z0-9_-]{43}"));
            var listed = manager.send(request(path, null).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listed.statusCode());
            assertFalse(listed.body().contains("bearerToken"));
            assertTrue(listed.body().contains(id.toString()));
            assertTrue(get(token, "/kitchen/orders", 200).path("tickets").toString().contains(order));
            get(token, "/kitchen/assistance", 200);
            problem(send("ws_" + token(), "GET", "/kitchen/orders", null, null), 401, "UNAUTHENTICATED");
            problem(send(token, "PATCH", itemPath(item), transition("PREPARING", 1), null), 403, "FORBIDDEN");
            problem(send(token, "POST", "/kitchen/items/" + item + "/allergy-review", object(), null), 403, "FORBIDDEN");
            for (String action : new String[]{"rush", "delayed", "handoff"}) {
                problem(send(token, "POST", "/kitchen/orders/" + order + "/" + action, object(), null), 403, "FORBIDDEN");
            }
            for (String restricted : new String[]{"/kitchen/history", "/kitchen/reviews", "/sessions/" + session}) {
                problem(send(token, "GET", restricted, null, null), 403, "FORBIDDEN");
            }
            assertEquals(401, send(token, "GET", path, null, null).statusCode());
            assertEquals(401, send(token, "GET", "/admin/staff", null, null).statusCode());
            assertEquals(403, send(token, "POST", "/staff/payments/" + UUID.randomUUID() + "/confirm-cash",
                    object(), null).statusCode());
            var revoke = request(path + "/" + id, null).header("X-CSRF-TOKEN", csrf).DELETE().build();
            assertEquals(204, manager.send(revoke, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(204, manager.send(revoke, HttpResponse.BodyHandlers.discarding()).statusCode());
            for (String queue : new String[]{"/kitchen/orders", "/kitchen/assistance"}) {
                problem(send(token, "GET", queue, null, null), 401, "UNAUTHENTICATED");
            }
            assertEquals("ordering-manager-test", db.sql("SELECT revoked_by FROM kitchen_workstation WHERE credential_id = :id")
                    .param("id", id).query(String.class).single());
            get(STAFF_TOKEN, "/kitchen/orders", 200);
        }
    }

    private static JsonNode issueWorkstation(HttpClient manager, String csrf, String label) throws Exception {
        var request = request("/admin/kitchen-workstations", null).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(object().put("label", label).toString())).build();
        var response = manager.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return json(response, 201);
    }

    @Test
    void javaFxKitchenDiscoversPersistedAssistanceAndCustomerReadsEveryTransition() throws Exception {
        startFx();
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        JsonNode workstation;
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            workstation = issueWorkstation(manager, csrf, "Assistance display");
        }
        String bearer = workstation.path("bearerToken").asText();
        UUID workstationId = UUID.fromString(workstation.at("/workstation/id").asText());
        var kitchen = LoopbackKitchenClient.create(server.getWebServer().getPort(), bearer, tls.trust());
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
            problem(send(bearer, "PATCH", "/kitchen/assistance/" + id, transition("ACKNOWLEDGED", 1), null),
                    409, "VERSION_CONFLICT");
            problem(send(bearer, "PATCH", "/kitchen/assistance/" + id, transition("ACKNOWLEDGED", 2), null),
                    409, "INVALID_TRANSITION");
            click(root, "Start", id);
            awaitKitchen(() -> statusIs(terminal, session, id, "IN_PROGRESS"));
            click(root, "Complete", id);
            awaitKitchen(() -> statusIs(terminal, session, id, "COMPLETED") && uiText(root, "Completed"));
            assertEquals(3L, db.sql("""
                    SELECT count(*) FROM assistance_transition a
                    JOIN kitchen_workstation w ON w.credential_id = a.staff_credential_id
                    WHERE a.request_id = CAST(:id AS uuid) AND w.credential_id = :workstation AND a.occurred_at IS NOT NULL
                    """).param("id", id).param("workstation", workstationId).query(Long.class).single());
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
        var chef = LoopbackKitchenClient.create(server.getWebServer().getPort(), STAFF_TOKEN, tls.trust());
        chef.signIn("regression-allergy-chef", CHEF_PASSWORD).get(10, TimeUnit.SECONDS);
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
            assertTrue(uiText(details, "Responsible Chef: Unassigned"));
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
        var kitchen = LoopbackKitchenClient.create(server.getWebServer().getPort(), PREP_TOKEN, tls.trust());
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
        problem(send(PREP_TOKEN, "PATCH", itemPath(item), transition("READY", 1), null), 409, "VERSION_CONFLICT");
        assertEquals("READY", patch(item, "READY", 2, 200).at("/order/status").asText());
        problem(send(PREP_TOKEN, "PATCH", itemPath(item), transition("SERVED", 3), null), 409, "INVALID_TRANSITION");
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
        problem(send(PREP_TOKEN, "PATCH", itemPath(item), transition("PREPARING", 1), null), 409, "REVIEW_REQUIRED");
        problem(send(PREP_TOKEN, "PATCH", itemPath(item), transition("READY", 1), null), 409, "REVIEW_REQUIRED");
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
        problem(send(PREP_TOKEN, "PATCH", itemPath(first), transition("PREPARING", 1), null), 409, "REVIEW_REQUIRED");
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
                """).param("hash", digest(CHEF_RAW)).query(Long.class).single());
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

    @Test
    void estimatesMeasuredDraftAndAcceptedPreparationFromPostgres() throws Exception {
        String terminalToken = terminal(true);
        String session = session(terminalToken, "DINE_IN", 201);
        UUID menu = dish();
        var input = draft(session, menu);
        assertEquals("MISSING_PREP_PROFILE", json(send(terminalToken, "POST", "/wait-estimates", input, null), 200)
                .path("reason").asText());
        UUID station = db.sql("SELECT id FROM kitchen_station WHERE code='HOT'").query(UUID.class).single();
        db.sql("""
                INSERT INTO kitchen_prep_profile(menu_item_id,station_id,duration_seconds,upper_duration_seconds)
                VALUES (:menu,:station,120,180)
                """).param("menu", menu).param("station", station).update();
        var prior = db.sql("""
                SELECT id, preparation_status FROM accepted_order_item WHERE preparation_status IN ('RECEIVED','PREPARING')
                AND allergy_review_status IN ('NOT_REQUIRED','APPROVED_FOR_PREPARATION')
                """)
                .query((row, index) -> java.util.Map.entry(row.getObject(1, UUID.class), row.getString(2))).list();
        prior.forEach(row -> db.sql("UPDATE accepted_order_item SET preparation_status='READY' WHERE id=:id")
                .param("id", row.getKey()).update());
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            var csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            String chef = staffWrite(manager, csrf, "POST", "", object().put("username", "estimate-chef").put("role", "CHEF").put("password", token()), 201)
                    .at("/account/id").asText();
            db.sql("UPDATE kitchen_station SET parallel_slots=2 WHERE id=:id").param("id", station).update();
            db.sql("INSERT INTO kitchen_station_chef(station_id,chef_id) VALUES (:station,CAST(:chef AS uuid))")
                    .param("station", station).param("chef", chef).update();
            db.sql("UPDATE staff_account SET available_until=NULL WHERE id=:chef").param("chef", PREP_CHEF).update();
            assertEquals("NO_CAPACITY", json(send(terminalToken, "POST", "/wait-estimates", input, null), 200).path("reason").asText());
            configWrite(manager, csrf, "/chef-availability/" + chef, object().put("minutes", 60).put("expectedVersion", 1), 200);
            ((ObjectNode) input.at("/lines/0")).put("quantity", 2);
            var estimate = json(send(terminalToken, "POST", "/wait-estimates", input, null), 200);
            assertEquals(4, estimate.path("minimumMinutes").asInt());
            assertEquals(6, estimate.path("maximumMinutes").asInt());
            UUID secondMenu = dish();
            UUID secondStation = db.sql("SELECT id FROM kitchen_station WHERE code='COLD'").query(UUID.class).single();
            db.sql("UPDATE kitchen_station SET parallel_slots=2 WHERE id=:id").param("id", secondStation).update();
            db.sql("INSERT INTO kitchen_station_chef(station_id,chef_id) VALUES (:station,CAST(:chef AS uuid))")
                    .param("station", secondStation).param("chef", chef).update();
            db.sql("""
                    INSERT INTO kitchen_prep_profile(menu_item_id,station_id,duration_seconds,upper_duration_seconds)
                    VALUES (:menu,:station,120,180)
                    """).param("menu", secondMenu).param("station", secondStation).update();
            var both = input.deepCopy();
            both.withArray("lines").add(draft(session, secondMenu).at("/lines/0"));
            assertEquals(9, json(send(terminalToken, "POST", "/wait-estimates", both, null), 200).path("maximumMinutes").asInt());
            String secondChef = staffWrite(manager, csrf, "POST", "", object().put("username", "estimate-specialist")
                    .put("role", "CHEF").put("password", token()), 201).at("/account/id").asText();
            db.sql("INSERT INTO kitchen_station_chef(station_id,chef_id) VALUES (:station,CAST(:chef AS uuid))")
                    .param("station", secondStation).param("chef", secondChef).update();
            configWrite(manager, csrf, "/chef-availability/" + secondChef, object().put("minutes", 60).put("expectedVersion", 1), 200);
            assertEquals(6, json(send(terminalToken, "POST", "/wait-estimates", both, null), 200).path("maximumMinutes").asInt());
            var quote = json(send(terminalToken, "POST", "/order-quotes", input, null), 200);
            var accepted = json(send(terminalToken, "POST", "/orders", submitBody(input, quote), UUID.randomUUID()), 201);
            String path = "/orders/" + accepted.path("id").asText() + "/wait-estimate";
            String item = accepted.at("/lines/0/id").asText();
            assertEquals(6, get(terminalToken, path, 200).path("maximumMinutes").asInt());
            problem(send(terminal(true), "GET", path, null, null), 404, "NOT_FOUND");
            String later = submitNew(terminalToken, session, menu);
            String laterEstimate = "/orders/" + later + "/wait-estimate";
            assertEquals(9, get(terminalToken, laterEstimate, 200).path("maximumMinutes").asInt());
            var rush = object().put("priority", "RUSH").put("reason", "Timing fixture").put("expectedVersion", 1);
            json(manager.send(managerPost("/admin/orders/" + later + "/rush", csrf, rush), HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals(3, get(terminalToken, laterEstimate, 200).path("maximumMinutes").asInt());
            patch(item, "PREPARING", 1, 200);
            db.sql("UPDATE accepted_order_item SET preparation_started_at=now() - interval '2 minutes' WHERE id=CAST(:id AS uuid)")
                    .param("id", item).update();
            assertEquals(4, get(terminalToken, path, 200).path("maximumMinutes").asInt());
            patch(item, "READY", 2, 200);
            assertEquals(0, get(terminalToken, path, 200).path("maximumMinutes").asInt());
            input.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
            assertEquals("ALLERGY_REVIEW", json(send(terminalToken, "POST", "/wait-estimates", input, null), 200).path("reason").asText());
        } finally {
            prior.forEach(row -> db.sql("UPDATE accepted_order_item SET preparation_status=:state WHERE id=:id")
                    .param("state", row.getValue()).param("id", row.getKey()).update());
        }
    }

    private static ObjectNode submitBody(ObjectNode draft, JsonNode quote) {
        return draft.deepCopy().put("quoteId", quote.path("quoteId").asText()).put("allergyReviewed", true);
    }

    private static String submitNew(String terminal, String session, UUID dish) throws Exception {
        ObjectNode draft = draft(session, dish);
        JsonNode quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        return json(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 201)
                .path("id").asText();
    }

    @Test
    void settlesCashOnceWithImmutableReceiptAndKeepsReviewEligibilityAfterClosing() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        String prefix = "/sessions/" + session;
        UUID menu = dish();
        String order = submitNew(terminal, session, menu);
        var draft = draft(session, menu);
        var quote = json(send(terminal, "POST", "/order-quotes", draft, null), 200);
        var balance = get(terminal, prefix + "/payable-balance", 200);
        long version = balance.path("billVersion").asLong();
        assertEquals(21025, balance.path("outstandingMinor").asLong());
        var request = object().put("method", "CASH").put("expectedBillVersion", version);
        var key = UUID.randomUUID();
        problem(send(terminal, "POST", prefix + "/payments", request.deepCopy().put("method", "MPESA"), key),
                422, "PAYMENT_METHOD_UNAVAILABLE");
        var attempt = json(send(terminal, "POST", prefix + "/payments", request, key), 201);
        assertEquals(attempt, json(send(terminal, "POST", prefix + "/payments", request, key), 200));
        problem(send(terminal, "POST", prefix + "/payments", request, UUID.randomUUID()), 409, "PAYMENT_PENDING");
        problem(send(terminal, "POST", "/orders", submitBody(draft, quote), UUID.randomUUID()), 409, "PAYMENT_PENDING");
        String payment = attempt.path("id").asText();
        String path = "/staff/payments/" + payment + "/confirm-cash";
        var confirmation = object().put("expectedVersion", 1).put("expectedBillVersion", version);
        assertEquals(403, send(STAFF_TOKEN, "POST", path, confirmation, null).statusCode());
        problem(send(terminal(true), "GET", prefix + "/payments/" + payment, null, null), 404, "NOT_FOUND");
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            String csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            var call = managerPost(path, csrf, confirmation);
            var first = manager.sendAsync(call, HttpResponse.BodyHandlers.ofString());
            var second = manager.sendAsync(call, HttpResponse.BodyHandlers.ofString());
            var paid = json(first.get(10, TimeUnit.SECONDS), 200);
            assertEquals(paid, json(second.get(10, TimeUnit.SECONDS), 200));
            assertEquals("SUCCEEDED", paid.path("status").asText());
            String receipt = paid.path("receiptId").asText();
            var document = get(terminal, prefix + "/receipts/" + receipt, 200);
            assertEquals(21025, document.path("amountMinor").asLong());
            assertEquals(order, document.at("/lines/0/orderId").asText());
            assertThrows(org.springframework.dao.DataAccessException.class, () -> db.sql("""
                    UPDATE payment_receipt SET receipt='{}' WHERE id=CAST(:id AS uuid)
                    """).param("id", receipt).update());
            assertEquals(0, get(terminal, prefix + "/payable-balance", 200).path("outstandingMinor").asLong());
            String close = "/admin/sessions/" + session + "/close";
            var closeInput = object().put("expectedBillVersion", version + 1);
            problem(manager.send(managerPost(close, csrf, closeInput), HttpResponse.BodyHandlers.ofString()), 409, "HANDOFF_REQUIRED");
            String item = get(terminal, "/orders/" + order, 200).at("/lines/0/id").asText();
            patch(item, "PREPARING", 1, 200);
            patch(item, "READY", 2, 200);
            json(send(HANDOFF_TOKEN, "POST", "/kitchen/orders/" + order + "/handoff", transition("SERVED", 3), null), 200);
            assertEquals("CLOSED", json(manager.send(managerPost(close, csrf, closeInput), HttpResponse.BodyHandlers.ofString()), 200)
                    .path("status").asText());
            assertTrue(get(terminal, prefix + "/review", 200).path("eligible").asBoolean());
            assertEquals(document, get(terminal, prefix + "/receipts/" + receipt, 200));
            var detail = json(manager.send(request("/admin/orders/" + order, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals("SUCCESSFUL", detail.at("/order/paymentStatus").asText());
            assertEquals(receipt, detail.at("/payments/0/receipt/number").asText());
            assertEquals(0, detail.at("/sessionBill/outstandingMinor").asLong());
            var page = json(manager.send(request("/admin/orders?q=" + order, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals(21025, page.at("/summary/confirmedSalesMinor").asLong());
            assertEquals(0, page.at("/summary/outstandingMinor").asLong());
        }
    }

    @Test
    void managerRegistersAndSafelyDisablesTerminalsWithAuthoritativeVisitMonitoring() throws Exception {
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            String csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            var table = json(manager.send(managerPost("/admin/tables", csrf, object().put("displayNumber", "Managed 19")),
                    HttpResponse.BodyHandlers.ofString()), 201);
            String tableId = table.path("id").asText();
            var registered = json(manager.send(managerPost("/admin/terminals", csrf,
                    object().put("label", "Managed terminal").put("tableId", tableId)), HttpResponse.BodyHandlers.ofString()), 201);
            String bearer = registered.path("bearerToken").asText();
            String terminalId = registered.path("id").asText();
            assertEquals(tableId, get(bearer, "/device", 200).at("/table/id").asText());
            String session = session(bearer, "DINE_IN", 201);
            var sessions = json(manager.send(request("/admin/sessions", null).GET().build(), HttpResponse.BodyHandlers.ofString()), 200);
            assertTrue(sessions.toString().contains(session));
            var disable = object().put("label", "Managed terminal").put("tableId", tableId).put("enabled", false).put("expectedVersion", 1);
            configWrite(manager, csrf, "/terminals/" + terminalId, disable, 409);
            json(manager.send(managerPost("/admin/sessions/" + session + "/close", csrf, object().put("expectedBillVersion", 1)),
                    HttpResponse.BodyHandlers.ofString()), 200);
            configWrite(manager, csrf, "/terminals/" + terminalId, disable, 200);
            problem(send(bearer, "GET", "/device", null, null), 401, "UNAUTHENTICATED");
            var dashboard = json(manager.send(request("/admin/dashboard", null).GET().build(), HttpResponse.BodyHandlers.ofString()), 200);
            assertTrue(dashboard.path("activeDiningSessions").isIntegralNumber());
            assertTrue(dashboard.path("salesToday").asText().startsWith("KSh "));
        }
    }

    private static HttpRequest managerPost(String path, String csrf, JsonNode value) {
        return request(path, null).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(value.toString())).build();
    }

    @Test
    void publishesVersionedCatalogTranslationsWithoutChangingAcceptedCatalogFields() throws Exception {
        UUID menu = dish();
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            String csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            var body = object().put("targetType", "DISH").put("targetId", menu.toString()).put("locale", "ar")
                    .put("name", "شاي").put("description", "مشروب ساخن").put("expectedVersion", 0);
            assertEquals(1, configWrite(manager, csrf, "/translations", body, 200).path("version").asInt());
            configWrite(manager, csrf, "/translations", body, 409);
            var visible = json(send(null, "GET", "/menu-translations", null, null), 200).path("translations");
            assertTrue(visible.valueStream().anyMatch(row -> row.path("targetId").asText().equals(menu.toString())
                    && row.path("name").asText().equals("شاي")));
            assertEquals("Test tea", db.sql("SELECT name FROM catalog_item WHERE id=:id").param("id", menu).query(String.class).single());
            assertEquals(1L, db.sql("SELECT count(*) FROM catalog_translation_audit WHERE target_id=:id")
                    .param("id", menu).query(Long.class).single());
            assertEquals(401, send(STAFF_TOKEN, "GET", "/admin/translations", null, null).statusCode());
        }
    }

    @Test
    void blocksSettlementOnChefReviewAndRecordsAuthoritativeCashCancellationAndFailure() throws Exception {
        String terminal = terminal(true);
        String session = session(terminal, "DINE_IN", 201);
        String prefix = "/sessions/" + session;
        UUID menu = dish();
        var held = draft(session, menu);
        held.withObject("/visitAllergy").put("kind", "DISCLOSED").putArray("allergens").add("SOY");
        var quote = json(send(terminal, "POST", "/order-quotes", held, null), 200);
        var order = json(send(terminal, "POST", "/orders", submitBody(held, quote), UUID.randomUUID()), 201);
        var request = object().put("method", "CASH").put("expectedBillVersion", 2);
        problem(send(terminal, "POST", prefix + "/payments", request, UUID.randomUUID()), 409, "CHEF_DECISION_PENDING");
        String item = order.at("/lines/0/id").asText();
        json(send(CHEF_TOKEN, "POST", "/kitchen/items/" + item + "/allergy-review",
                object().put("decision", "APPROVE").put("reason", "Fixture verified controls").put("expectedVersion", 1), null), 200);
        var attempt = json(send(terminal, "POST", prefix + "/payments", request, UUID.randomUUID()), 201);
        String id = attempt.path("id").asText();
        var cancel = json(send(terminal, "POST", prefix + "/payments/" + id + "/cancel",
                object().put("expectedVersion", 1), null), 200);
        assertTrue(cancel.path("cancellationRequested").asBoolean());
        assertEquals("PENDING", cancel.path("status").asText());
        try (var manager = HttpClient.newBuilder().sslContext(tls.trust()).cookieHandler(new CookieManager()).build()) {
            managerLogin(manager);
            String csrf = manager.send(request("/admin/csrf", null).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            var resolution = object().put("expectedVersion", 2).put("status", "CANCELLED").put("reason", "No cash received");
            var cancelled = json(manager.send(managerPost("/admin/payments/" + id + "/resolve-cash", csrf, resolution),
                    HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals("CANCELLED", cancelled.path("status").asText());
            var next = json(send(terminal, "POST", prefix + "/payments", request, UUID.randomUUID()), 201);
            var failed = json(manager.send(managerPost("/admin/payments/" + next.path("id").asText() + "/resolve-cash", csrf,
                    resolution.put("expectedVersion", 1).put("status", "FAILED")), HttpResponse.BodyHandlers.ofString()), 200);
            assertEquals("FAILED", failed.path("status").asText());
            assertTrue(failed.path("receiptId").isNull());
            assertEquals(21025, get(terminal, prefix + "/payable-balance", 200).path("outstandingMinor").asLong());
        }
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
        var id = UUID.fromString(item);
        db.sql("""
                UPDATE accepted_order_item SET claimed_by=:chef,
                    station_id=COALESCE(station_id,(SELECT id FROM kitchen_station WHERE code='HOT')),
                    planned_duration_seconds=COALESCE(planned_duration_seconds,60)
                WHERE id=:id
                """).param("chef", PREP_CHEF).param("id", id).update();
        db.sql("""
                INSERT INTO kitchen_station_chef(station_id,chef_id)
                SELECT station_id,:chef FROM accepted_order_item WHERE id=:id ON CONFLICT DO NOTHING
                """).param("chef", PREP_CHEF).param("id", id).update();
        db.sql("UPDATE staff_account SET available_until=now()+interval '1 minute' WHERE id=:chef")
                .param("chef", PREP_CHEF).update();
        return json(send(PREP_TOKEN, "PATCH", itemPath(item), transition(state, version), null), status);
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
