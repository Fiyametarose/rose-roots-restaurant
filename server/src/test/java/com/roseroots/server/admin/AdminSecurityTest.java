package com.roseroots.server.admin;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminSecurityTest {
    private static final String ORDERS = AdminSecurity.PREFIX + "/orders";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("rose.admin.manager.username", () -> "manager-test");
        registry.add("rose.admin.manager.password-hash", () -> HASH);
        registry.add("rose.admin.kitchen.username", () -> "kitchen-test");
        registry.add("rose.admin.kitchen.password-hash", () -> HASH);
    }

    @Test
    void anonymousAndKitchenStaffCannotReadDashboard() throws Exception {
        try (HttpClient client = client()) {
            assertEquals(401, get(client, AdminSecurity.DASHBOARD).statusCode());
            assertEquals(403, login(client, "kitchen-test", PASSWORD).statusCode());
            assertEquals(401, get(client, AdminSecurity.DASHBOARD).statusCode());
        }
    }

    @Test
    void incorrectPasswordAndMissingCsrfAreRejected() throws Exception {
        try (HttpClient client = client()) {
            assertEquals(401, login(client, "manager-test", "incorrect").statusCode());
            var missingToken = request(AdminSecurity.LOGIN).POST(HttpRequest.BodyPublishers.noBody());
            assertEquals(403, send(client, missingToken).statusCode());
            assertEquals(401, get(client, AdminSecurity.DASHBOARD).statusCode());
        }
    }

    @Test
    void managerCanReadUnavailableSnapshotAndLogoutInvalidatesSession() throws Exception {
        try (HttpClient client = client()) {
            assertEquals(204, login(client, "manager-test", PASSWORD).statusCode());
            var dashboard = get(client, AdminSecurity.DASHBOARD);
            assertEquals(200, dashboard.statusCode());
            assertTrue(dashboard.body().contains("\"ordersToday\":null"), dashboard.body());
            assertTrue(dashboard.body().contains("\"recentOrders\":null"), dashboard.body());

            var logout = request(AdminSecurity.LOGOUT).POST(HttpRequest.BodyPublishers.noBody());
            assertEquals(403, send(client, logout).statusCode());
            assertEquals(200, get(client, AdminSecurity.DASHBOARD).statusCode());
            logout.header("X-CSRF-TOKEN", get(client, AdminSecurity.CSRF).body());
            assertEquals(204, send(client, logout).statusCode());
            assertEquals(401, get(client, AdminSecurity.DASHBOARD).statusCode());
        }
    }

    @Test
    void operationalReadsRequireManagerAndHaveNoFinancialMutation() throws Exception {
        try (HttpClient client = client()) {
            String detail = ORDERS + "/" + UUID.randomUUID();
            assertEquals(401, get(client, ORDERS).statusCode());
            assertEquals(401, get(client, detail).statusCode());
            assertEquals(403, login(client, "kitchen-test", PASSWORD).statusCode());
            assertEquals(401, get(client, detail).statusCode());
            assertEquals(204, login(client, "manager-test", PASSWORD).statusCode());
            var absent = get(client, ORDERS);
            assertEquals(503, absent.statusCode(), absent.body());
            assertTrue(absent.body().contains("OPERATIONS_SOURCE_UNAVAILABLE"), absent.body());
            assertEquals(405, send(client, request(detail).header("X-CSRF-TOKEN", get(client, AdminSecurity.CSRF).body())
                    .method("PATCH", HttpRequest.BodyPublishers.noBody())).statusCode());
            assertEquals(204, send(client, request(AdminSecurity.LOGOUT)
                    .header("X-CSRF-TOKEN", get(client, AdminSecurity.CSRF).body())
                    .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            assertEquals(401, get(client, detail).statusCode());
        }
    }

    private HttpResponse<String> login(HttpClient client, String username, String password) throws Exception {
        String token = get(client, AdminSecurity.CSRF).body();
        return send(client, request(AdminSecurity.LOGIN).header("X-CSRF-TOKEN", token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)));
    }

    @Test
    void reviewsAreManagerOnlyAndProductionReportsMissingProvider() throws Exception {
        try (HttpClient client = client()) {
            String reviews = AdminSecurity.PREFIX + "/reviews";
            assertEquals(401, get(client, reviews).statusCode());
            assertEquals(403, login(client, "kitchen-test", PASSWORD).statusCode());
            assertEquals(401, get(client, reviews).statusCode());
            assertEquals(204, login(client, "manager-test", PASSWORD).statusCode());
            var unavailable = get(client, reviews);
            assertEquals(503, unavailable.statusCode());
            assertTrue(unavailable.body().contains("REVIEW_SOURCE_UNAVAILABLE"), unavailable.body());
            assertEquals(400, get(client, reviews + "?rating=0").statusCode());
            assertEquals(404, send(client, request(reviews + "/" + UUID.randomUUID())
                    .header("X-CSRF-TOKEN", get(client, AdminSecurity.CSRF).body())
                    .method("PATCH", HttpRequest.BodyPublishers.noBody())).statusCode());
            assertEquals(204, send(client, request(AdminSecurity.LOGOUT)
                    .header("X-CSRF-TOKEN", get(client, AdminSecurity.CSRF).body())
                    .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            assertEquals(401, get(client, reviews).statusCode());
        }
    }

    private HttpResponse<String> get(HttpClient client, String route) throws Exception {
        return send(client, request(route).GET());
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String route) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route));
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }
}
