package com.roseroots.customer.services.api;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roseroots.customer.models.*;
import com.roseroots.customer.services.OrderService;
import com.roseroots.customer.services.OrderTrackingService;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectedRestTest {
    @TempDir Path temporary;

    @Test void authenticatedHttpsExercisesCatalogSessionsReplayTimeoutTrackingAndBill() throws Exception {
        var fixture = new Fixture();
        var keyStore = tlsKeys();
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "fixture-password".toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keyStore);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        var polls = Executors.newSingleThreadScheduledExecutor();
        server.setExecutor(workers);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/api/v1/", fixture::respond);
        server.start();
        try {
            var address = new BackendAddress(java.net.URI.create("https://localhost:" + server.getAddress().getPort()));
            var http = HttpClient.newBuilder().sslContext(ssl).build();
            var rest = new SpringBootOrderService(address.api(), () -> "test-provisioned-token", () -> true, http,
                    Duration.ofSeconds(2));
            var denied = new SpringBootOrderService(address.api(), () -> "wrong-token", () -> true, http,
                    Duration.ofSeconds(2));
            assertProblem("UNAUTHENTICATED", () -> get(new ConnectedTerminal(denied, Clock.systemUTC())
                    .openOrResume(ServiceMode.DINE_IN)));
            var terminal = new ConnectedTerminal(rest, Clock.systemUTC());
            var session = get(terminal.openOrResume(ServiceMode.DINE_IN));
            assertEquals("07", session.device().table().displayNumber());
            assertEquals(fixture.session, session.sessionId());
            assertEquals(session, get(terminal.openOrResume(ServiceMode.DINE_IN)));

            var catalog = get(new ConnectedCatalog(rest, address).loadCatalog());
            var dish = catalog.items().getFirst();
            assertEquals(fixture.dish, dish.id());
            assertEquals(fixture.option, dish.customizationGroups().getFirst().choices().getFirst().id());
            assertFalse(dish.safety().allergenDataComplete());

            var input = new Ordering.Line(UUID.fromString(fixture.line), dish.id(), 2, Map.of(fixture.group, Set.of(fixture.option)),
                    new AllergyDeclaration(true, Set.of(), ""), "Less oil");
            var draft = new Ordering.Draft(UUID.fromString(session.sessionId()), session.mode(),
                    new AllergyDeclaration(true, Set.of(), ""), List.of(input));
            var quote = get(rest.quote(draft));
            assertEquals(314000, quote.totals().totalMinor());
            var key = UUID.randomUUID();
            assertProblem("CONNECTION_INTERRUPTED", () -> get(rest.submit(draft, quote.id(), key)));
            var accepted = get(rest.submit(draft, quote.id(), key));
            assertEquals(1, fixture.committed.size());
            assertEquals(accepted, get(rest.submit(draft, quote.id(), key)));
            var changed = new Ordering.Draft(draft.sessionId(), draft.mode(), draft.visitAllergy(), List.of(
                    new Ordering.Line(input.clientLineId(), input.menuItemId(), 1, input.selections(), input.allergy(), input.instructions())));
            assertProblem("IDEMPOTENCY_CONFLICT", () -> get(rest.submit(changed, quote.id(), key)));
            assertEquals(accepted.id(), get(rest.order(draft.sessionId(), accepted.id())).id());
            assertProblem("INVALID_RESPONSE", () -> get(rest.order(UUID.randomUUID(), accepted.id())));

            var updates = new LinkedBlockingQueue<OrderTrackingService.Update>();
            try (var watcher = new RestOrderTracking(rest, polls).watch(draft.sessionId(), updates::add)) {
                assertEquals(OrderTrackingService.State.LOADING, next(updates).state());
                assertEquals(Ordering.Status.RECEIVED, next(updates).history().orders().getFirst().status());
                fixture.stage = "PREPARING";
                assertEquals(Ordering.Status.PREPARING, next(updates).history().orders().getFirst().status());
                fixture.stage = "READY";
                var ready = next(updates).history();
                assertEquals(Ordering.Status.READY, ready.orders().getFirst().lines().getFirst().preparation());
                assertEquals(314000, ready.totals().totalMinor());
            }
            verifyAssistance(rest, fixture, session, polls);
            server.stop(0);
            assertProblem("CONNECTION_INTERRUPTED", () -> get(rest.order(draft.sessionId(), accepted.id())));
        } finally {
            server.stop(0);
            polls.shutdownNow();
            workers.shutdownNow();
        }
    }

    private static void verifyAssistance(SpringBootOrderService rest, Fixture fixture, TerminalSession session,
                                         ScheduledExecutorService polls) throws Exception {
        var client = new RestAssistanceClient(rest, polls);
        var scope = com.roseroots.customer.services.AssistanceService.Context.from(session);
        var key = UUID.randomUUID();
        var command = new com.roseroots.customer.services.AssistanceService.Command(scope, key,
                com.roseroots.customer.services.AssistanceService.Type.OTHER, "A high chair", Instant.now());
        var accepted = get(client.submit(command));
        assertEquals("SUBMITTED", accepted.status().name());
        assertEquals("A high chair", accepted.message());
        assertEquals(accepted, get(client.submit(command)));
        assertEquals(1, fixture.assistancePosts.size());
        var updates = new LinkedBlockingQueue<com.roseroots.customer.services.AssistanceService.Update>();
        try (var watch = client.subscribeAndReload(scope, updates::add)) {
            for (var status : List.of("SUBMITTED", "ACKNOWLEDGED", "IN_PROGRESS", "COMPLETED")) {
                fixture.assistanceStatus = status;
                var update = updates.poll(7, TimeUnit.SECONDS);
                assertNotNull(update);
                assertEquals("LIVE", update.connection().name());
                assertEquals(accepted.id(), update.requests().getFirst().id());
                assertEquals(status, update.requests().getFirst().status().name());
            }
        }
        try (var reopened = client.subscribeAndReload(scope, updates::add)) {
            var update = updates.poll(7, TimeUnit.SECONDS);
            assertNotNull(update);
            assertEquals("COMPLETED", update.requests().getFirst().status().name());
        }
    }

    private KeyStore tlsKeys() throws Exception {
        var file = temporary.resolve("https-fixture.p12");
        var executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        var process = new ProcessBuilder(executable.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "fixture-password", "-noprompt").redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) { store.load(input, "fixture-password".toCharArray()); }
        return store;
    }

    private static <T> T get(CompletionStage<T> result) throws Exception {
        return result.toCompletableFuture().get(7, TimeUnit.SECONDS);
    }

    private static OrderTrackingService.Update next(BlockingQueue<OrderTrackingService.Update> updates) throws Exception {
        var value = updates.poll(7, TimeUnit.SECONDS);
        assertNotNull(value);
        return value;
    }

    private static void assertProblem(String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ExecutionException.class, action);
        assertEquals(code, assertInstanceOf(OrderService.Problem.class, error.getCause()).code());
    }

    private static final class Fixture {
        final JsonObject paths;
        final JsonObject device;
        final JsonObject sessionBody;
        final JsonObject quote;
        final JsonObject accepted;
        final JsonObject bill;
        final String session;
        final String line;
        final String dish;
        final String group;
        final String option;
        final Map<String, JsonObject> committed = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        volatile String stage = "RECEIVED";
        volatile String assistanceStatus = "SUBMITTED";
        final Map<String, JsonObject> assistancePosts = new ConcurrentHashMap<>();

        Fixture() throws IOException {
            paths = JsonParser.parseString(Files.readString(Path.of("docs/ordering-api-v1.openapi.json")))
                    .getAsJsonObject().getAsJsonObject("paths");
            device = example("/device", "get", "200");
            sessionBody = example("/sessions/{id}", "get", "200");
            sessionBody.addProperty("expiresAt", Instant.now().plusSeconds(3600).toString());
            session = sessionBody.get("id").getAsString();
            quote = example("/order-quotes", "post", "200");
            quote.addProperty("expiresAt", Instant.now().plusSeconds(3600).toString());
            accepted = example("/orders", "post", "201");
            var eligible = accepted.getAsJsonArray("lines").get(0).getAsJsonObject();
            eligible.add("allergy", accepted.get("visitAllergy").deepCopy());
            eligible.addProperty("allergyReviewStatus", "NOT_REQUIRED");
            eligible.add("allergyRisk", JsonParser.parseString(
                    "{\"ingredientMatches\":[],\"crossContactMatches\":[],\"unresolved\":false}"));
            bill = example("/sessions/{id}/bill", "get", "200");
            var priced = quote.getAsJsonArray("lines").get(0).getAsJsonObject();
            line = priced.get("clientLineId").getAsString();
            dish = priced.get("menuItemId").getAsString();
            var selection = priced.getAsJsonArray("options").get(0).getAsJsonObject();
            group = selection.get("groupId").getAsString();
            option = selection.get("optionId").getAsString();
        }

        private JsonObject example(String path, String method, String status) {
            return paths.getAsJsonObject(path).getAsJsonObject(method).getAsJsonObject("responses").getAsJsonObject(status)
                    .getAsJsonObject("content").getAsJsonObject("application/json").getAsJsonObject("example").deepCopy();
        }

        void respond(HttpExchange http) throws IOException {
            try (http) {
                if (!"Bearer test-provisioned-token".equals(http.getRequestHeaders().getFirst("Authorization"))) {
                    problem(http, 401, "UNAUTHENTICATED");
                    return;
                }
                var path = http.getRequestURI().getPath().substring("/api/v1/".length());
                switch (path) {
                    case "device" -> send(http, 200, device.toString(), "application/json");
                    case "sessions" -> {
                        if (!"{\"mode\":\"DINE_IN\"}".equals(body(http).toString())) {
                            problem(http, 422, "INVALID_SELECTION");
                            return;
                        }
                        send(http, opens.getAndIncrement() == 0 ? 201 : 200, sessionBody.toString(), "application/json");
                    }
                    case "menu" -> send(http, 200, catalog(), "application/json");
                    case "assistance" -> {
                        var payload = body(http);
                        if (!session.equals(payload.get("sessionId").getAsString())
                                || !"OTHER".equals(payload.get("type").getAsString())
                                || payload.size() != 3 || !payload.has("note")) {
                            problem(http, 422, "INVALID_SELECTION");
                            return;
                        }
                        var previous = assistancePosts.putIfAbsent(http.getRequestHeaders().getFirst("Idempotency-Key"), payload);
                        if (previous != null && !previous.equals(payload)) {
                            problem(http, 409, "IDEMPOTENCY_CONFLICT");
                            return;
                        }
                        send(http, previous == null ? 201 : 200, assistance("SUBMITTED").toString(), "application/json");
                    }
                    case "order-quotes" -> {
                        var item = body(http).getAsJsonArray("lines").get(0).getAsJsonObject();
                        if (!dish.equals(item.get("menuItemId").getAsString())) {
                            problem(http, 422, "INVALID_SELECTION");
                            return;
                        }
                        send(http, 200, quote.toString(), "application/json");
                    }
                    case "orders" -> {
                        String key = http.getRequestHeaders().getFirst("Idempotency-Key");
                        var payload = body(http);
                        var previous = committed.putIfAbsent(key, payload);
                        if (previous == null) {
                            try { Thread.sleep(3000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            send(http, 201, accepted.toString(), "application/json");
                        } else if (previous.equals(payload)) {
                            send(http, 200, accepted.toString(), "application/json");
                        } else {
                            problem(http, 409, "IDEMPOTENCY_CONFLICT");
                        }
                    }
                    default -> {
                        if (path.equals("sessions/" + session + "/assistance")) {
                            send(http, 200, "{\"requests\":[" + assistance(assistanceStatus) + "],\"nextCursor\":null}", "application/json");
                        } else if (path.equals("sessions/" + session + "/bill")) send(http, 200, bill.toString(), "application/json");
                        else if (path.equals("sessions/" + session + "/orders")) {
                            send(http, 200, "{\"orders\":[" + current() + "],\"nextCursor\":null}", "application/json");
                        } else if (path.equals("orders/" + accepted.get("id").getAsString())) {
                            send(http, 200, current().toString(), "application/json");
                        } else problem(http, 404, "NOT_FOUND");
                    }
                }
            }
        }

        private JsonObject assistance(String status) {
            int version = com.roseroots.customer.services.AssistanceService.Status.valueOf(status).ordinal() + 1;
            var data = new JsonObject();
            data.addProperty("id", "00000000-0000-4000-8000-000000000101");
            data.addProperty("sessionId", session);
            data.addProperty("mode", "DINE_IN");
            data.addProperty("serviceDestination", "Table 07");
            data.addProperty("type", "OTHER");
            data.addProperty("note", assistancePosts.values().iterator().next().get("note").getAsString());
            data.addProperty("status", status);
            var submitted = Instant.parse("2026-11-21T18:10:00Z");
            data.addProperty("submittedAt", submitted.toString());
            data.addProperty("updatedAt", submitted.plusSeconds(version - 1).toString());
            data.addProperty("version", version);
            return data;
        }

        private JsonObject current() {
            var view = accepted.deepCopy();
            view.addProperty("status", stage);
            view.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("preparationStatus", stage);
            return view;
        }

        private String catalog() {
            return """
                    {"currency":"KES","categories":[{"id":"00000000-0000-4000-8000-000000000099","parentId":null,"name":"Mains"}],
                     "items":[{"id":"%s","categoryId":"00000000-0000-4000-8000-000000000099","name":"Doro Wat",
                     "description":"Chicken stew","price":1450.00,"cuisine":"ETHIOPIAN","allergens":["EGGS"],"dietaryTags":[],
                     "ingredients":["Chicken"],"availability":"AVAILABLE","customizationGroups":[{"id":"%s","name":"Extras",
                     "selection":"EXTRA","minimum":0,"maximum":1,"choices":[{"id":"%s","name":"Yogurt","priceAdjustment":120}]}]}]}
                    """.formatted(dish, group, option);
        }

        private static JsonObject body(HttpExchange http) throws IOException {
            return JsonParser.parseString(new String(http.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }

        private static void problem(HttpExchange http, int status, String code) throws IOException {
            send(http, status, "{\"status\":" + status + ",\"code\":\"" + code + "\"}", "application/problem+json");
        }

        private static void send(HttpExchange http, int status, String body, String contentType) throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            http.getResponseHeaders().set("Content-Type", contentType);
            http.sendResponseHeaders(status, bytes.length);
            http.getResponseBody().write(bytes);
        }
    }
}
