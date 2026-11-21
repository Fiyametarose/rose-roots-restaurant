package com.roseroots.server.admin;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static com.roseroots.server.admin.OperationsSource.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OperationsFixture.class)
class OperationsQueryTest {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final JsonMapper JSON = new JsonMapper();
    private static final String ORDERS = "/api/v1/admin/orders";

    @LocalServerPort int port;

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("rose.admin.manager.username", () -> "operations-test");
        registry.add("rose.admin.manager.password-hash", () -> HASH);
    }

    @Test
    void ordersKeepStatusSeparateAndConfirmedTotalsCoverAllResultsNotJustPage() throws Exception {
        try (var client = manager()) {
            var response = get(client, ORDERS + "?limit=1");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
            var page = JSON.readTree(response.body());
            assertEquals(3, page.get("summary").get("totalOrders").asInt());
            assertEquals(220000, page.get("summary").get("confirmedSalesMinor").asLong());
            assertEquals(2, page.get("summary").get("pendingPayments").asInt());
            assertEquals(1, page.get("summary").get("failedPayments").asInt());
            var row = page.get("items").get(0);
            assertEquals("READY", row.get("orderStatus").asString());
            assertEquals("UNPAID", row.get("paymentStatus").asString());
            var second = JSON.readTree(get(client, ORDERS + "?limit=1&cursor=1").body());
            assertEquals("RR-1041", second.get("items").get(0).get("orderNumber").asString());
        }
    }

    @Test
    void serverForwardsSearchDateStatusMethodTypeSortAndReturnsRealEmptyPage() throws Exception {
        try (var client = manager()) {
            var filtered = get(client, ORDERS + "?q=GRILLED&from=2026-11-20&to=2026-11-20"
                    + "&orderStatus=SERVED&paymentStatus=SUCCESSFUL&serviceType=DINE_IN&paymentMethod=CARD&sort=TOTAL_DESC");
            assertEquals(200, filtered.statusCode(), filtered.body());
            assertEquals("RR-1041", JSON.readTree(filtered.body()).get("items").get(0).get("orderNumber").asString());
            var empty = JSON.readTree(get(client, ORDERS + "?q=unknown").body());
            assertEquals(0, empty.get("items").size());
            assertEquals(0, empty.get("summary").get("totalOrders").asInt());
            assertEquals(400, get(client, ORDERS + "?from=2026-11-21&to=2026-11-20").statusCode());
        }
    }

    @Test
    void detailUsesHistoricalSnapshotsAndOnlyOwnerSuppliedReceipt() throws Exception {
        try (var client = manager()) {
            var ready = JSON.readTree(get(client, ORDERS + "/" + OperationsFixture.READY_ID).body());
            var line = ready.get("items").get(0);
            assertEquals(135000, line.get("baseUnitMinor").asLong());
            assertEquals(30000, line.get("selections").get(0).get("adjustmentMinor").asLong());
            assertEquals("Sauce on the side.", line.get("instructions").asString());
            assertTrue(ready.get("payments").get(0).get("receipt").isNull());
            var paid = JSON.readTree(get(client, ORDERS + "/" + OperationsFixture.SERVED_ID).body());
            assertEquals("FIXTURE-081", paid.get("payments").get(1).get("receipt").get("number").asString());
            assertEquals(404, get(client, ORDERS + "/" + UUID.randomUUID()).statusCode());
        }
    }

    @Test
    void settlementHelperCountsUniqueSuccessfulChargesOnly() {
        UUID charged = UUID.randomUUID();
        var ok = new Transaction(charged, "CARD", 145000L, null, null,
                PaymentStatus.SUCCESSFUL, TransactionKind.CHARGE, null, null, null);
        var pending = new Transaction(UUID.randomUUID(), "CASH", 145000L, null, null,
                PaymentStatus.PENDING, TransactionKind.CHARGE, null, null, null);
        var refund = new Transaction(UUID.randomUUID(), "CARD", 145000L, null, null,
                PaymentStatus.SUCCESSFUL, TransactionKind.REFUND, null, null, null);
        var failed = new Transaction(UUID.randomUUID(), "CARD", 145000L, null, null,
                PaymentStatus.FAILED, TransactionKind.CHARGE, null, null, null);
        assertEquals(145000, OperationsSource.confirmedSalesMinor(List.of(ok, ok, pending, refund, failed)));
    }

    private HttpClient manager() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var csrf = get(client, AdminSecurity.CSRF).body();
        var login = HttpRequest.newBuilder(uri(AdminSecurity.LOGIN)).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=operations-test&password=" + PASSWORD)).build();
        assertEquals(204, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
        return client;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
