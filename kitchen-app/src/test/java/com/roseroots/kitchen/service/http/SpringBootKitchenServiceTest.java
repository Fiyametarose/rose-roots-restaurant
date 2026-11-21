package com.roseroots.kitchen.service.http;

import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.service.KitchenSync;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static com.roseroots.kitchen.service.http.KitchenHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringBootKitchenServiceTest {
    @Test
    void automaticRefreshBacksOffRetainsCompleteDataAndRecoversWithoutRegressingVersions() throws Exception {
        var request = new AtomicInteger();
        var sentAt = new ConcurrentLinkedQueue<Long>();
        try (var fixture = new KitchenHttpFixture(exchange -> {
            sentAt.add(System.nanoTime());
            assertEquals("Bearer fixture-token", exchange.getRequestHeaders().getFirst("Authorization"));
            switch (request.incrementAndGet()) {
                case 1 -> reply(exchange, 200, "{\"tickets\":[],\"nextCursor\":null}");
                case 2 -> reply(exchange, 200, page(ticket("PREPARING", 2, false), null));
                case 3 -> reply(exchange, 200, page(ticket("RECEIVED", 1, false), null));
                case 4 -> reply(exchange, 503, "{}");
                case 5 -> reply(exchange, 200, page(ticket("READY", 3, false), null));
                default -> reply(exchange, 403, "{}");
            }
        })) {
            var service = fixture.pollingService(Duration.ofMillis(180), Duration.ofMillis(720));
            try {
                service.startAutomaticRefresh();
                await(() -> service.status() == KitchenSync.Status.FAILED && service.message().contains("older"));
                assertEquals(Status.PREPARING, service.activeTickets().getFirst().status());
                assertFalse(service.writesAvailable());
                await(() -> !service.activeTickets().isEmpty() && service.activeTickets().getFirst().status() == Status.READY);
                await(() -> service.status() == KitchenSync.Status.FAILED && service.message().contains("denied"));
                assertTrue(service.activeTickets().isEmpty());
            } finally {
                service.stop();
            }
            var timings = java.util.List.copyOf(sentAt);
            assertTrue(timings.size() >= 6);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(timings.get(3) - timings.get(2)) >= 330);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(timings.get(4) - timings.get(3)) >= 680);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(timings.get(5) - timings.get(4)) >= 150);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(timings.get(5) - timings.get(4)) < 680);
            int stoppedAt = request.get();
            Thread.sleep(800);
            assertEquals(stoppedAt, request.get());
        }
    }

    @Test
    void queuesOneExplicitActionDuringPollAndRequiresTheDisplayedVersion() throws Exception {
        var reads = new AtomicInteger();
        var writes = new AtomicInteger();
        var pollStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new KitchenHttpFixture(exchange -> {
            if (exchange.getRequestMethod().equals("PATCH")) {
                writes.incrementAndGet();
                reply(exchange, 200, ticket("PREPARING", 3, false).toString());
            } else if (reads.incrementAndGet() == 1) {
                reply(exchange, 200, page(ticket("RECEIVED", 1, false), null));
            } else {
                pollStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reply(exchange, 200, page(ticket("RECEIVED", 2, false), null));
            }
        })) {
            var service = fixture.pollingService(Duration.ofMillis(200), Duration.ofSeconds(2));
            try {
                service.startAutomaticRefresh();
                assertTrue(pollStarted.await(5, TimeUnit.SECONDS));
                var visible = service.activeTickets().getFirst();
                service.advanceItem(visible.id(), visible.items().getFirst());
                assertEquals(0, writes.get());
                release.countDown();
                await(() -> service.message().contains("changed during synchronization"));
                service.advanceItem(visible.id(), visible.items().getFirst());
                assertEquals(0, writes.get(), "An old rendering must not write using a newer version");
                var current = service.activeTickets().getFirst();
                service.advanceItem(current.id(), current.items().getFirst());
                await(() -> writes.get() == 1 && service.activeTickets().getFirst().status() == Status.PREPARING);
            } finally {
                release.countDown();
                service.stop();
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Expected synchronization state was not observed");
    }

    @Test
    void configurationUsesSharedOriginBeforeCustomerAliasAndRequiresVerifiedHttps() {
        assertEquals(KitchenBackendConfig.Mode.LOCAL, KitchenBackendConfig.read(Map.of()).mode());
        var connected = Map.of("ROSE_KITCHEN_MODE", "connected", "ROSE_ROOTS_BACKEND_URL", "https://staff-pc.example:8443");
        assertEquals("https://staff-pc.example:8443/", KitchenBackendConfig.read(connected).origin().toString());
        assertEquals("https://preferred.example/", KitchenBackendConfig.read(Map.of(
                "ROSE_KITCHEN_MODE", "connected", "ROSE_ROOTS_BACKEND_URL", "https://alias.example",
                "ROSE_BACKEND_URL", "https://preferred.example")).origin().toString());
        assertThrows(IllegalArgumentException.class, () -> KitchenBackendConfig.read(Map.of(
                "ROSE_KITCHEN_MODE", "connected", "ROSE_BACKEND_URL", "http://staff-pc.example")));
        assertThrows(IllegalArgumentException.class, () -> KitchenBackendConfig.read(Map.of(
                "ROSE_KITCHEN_MODE", "connected", "ROSE_BACKEND_URL", "https://staff-pc.example/api/v1")));
    }

    @Test
    void retrievesAllPagesAndUsesAuthoritativeIdentityAndVersionsInPatch() throws Exception {
        var requests = new ConcurrentLinkedQueue<String>();
        var writes = new AtomicInteger();
        try (var server = new KitchenHttpFixture(exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + " " + exchange.getRequestURI().getRawQuery() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (exchange.getRequestMethod().equals("PATCH")) {
                int write = writes.incrementAndGet();
                reply(exchange, 200, ticket(write == 1 ? "PREPARING" : "READY", write + 1, false).toString());
            } else if (exchange.getRequestURI().getRawQuery().contains("cursor=")) {
                var second = ticket("RECEIVED", 1, true);
                second.getAsJsonObject("order").addProperty("id", "00000000-0000-4000-8000-000000000090");
                second.getAsJsonObject("order").getAsJsonArray("lines").get(0).getAsJsonObject()
                        .addProperty("id", "00000000-0000-4000-8000-000000000091");
                reply(exchange, 200, page(second, null));
            } else {
                reply(exchange, 200, page(ticket("RECEIVED", 1, false), "a+/&"));
            }
        })) {
            var service = server.service(Duration.ofSeconds(4), Runnable::run);
            awaitOperation(service, service::refresh);
            assertEquals(KitchenSync.Status.CONNECTED, service.status());
            assertEquals(2, service.activeTickets().size());
            Ticket first = service.activeTickets().getFirst();
            assertEquals(ORDER_ID, first.reference());
            assertEquals("Table 07", first.destination());
            assertEquals(ITEM_ID, first.items().getFirst().server().id().toString());
            assertEquals("Sauce on side", first.items().getFirst().options());
            assertEquals("WHEAT", first.items().getFirst().crossContact());
            assertNull(first.items().getFirst().station());
            var held = service.activeTickets().getLast();
            service.advanceItem(held.id(), held.items().getFirst().id(), Status.RECEIVED);
            assertEquals(0, writes.get());
            awaitOperation(service, () -> service.advanceItem(first.id(), first.items().getFirst().id(), Status.RECEIVED));
            awaitOperation(service, () -> service.advanceItem(first.id(), first.items().getFirst().id(), Status.PREPARING));
            assertEquals(Status.READY, service.activeTickets().getFirst().status());
            assertEquals(3, service.activeTickets().getFirst().items().getFirst().server().version());
            assertTrue(requests.contains("GET /api/v1/kitchen/orders limit=50 Bearer fixture-token "));
            assertTrue(requests.contains("GET /api/v1/kitchen/orders limit=50&cursor=a%2B%2F%26 Bearer fixture-token "));
            assertTrue(requests.contains("PATCH /api/v1/kitchen/items/" + ITEM_ID
                    + "/status null Bearer fixture-token {\"status\":\"PREPARING\",\"expectedVersion\":1}"));
            assertTrue(requests.contains("PATCH /api/v1/kitchen/items/" + ITEM_ID
                    + "/status null Bearer fixture-token {\"status\":\"READY\",\"expectedVersion\":2}"));
        }
    }

    @Test
    void httpAuthenticationAndPermissionFailuresKeepWritesDisabled() throws Exception {
        var status = new AtomicInteger(401);
        try (var server = new KitchenHttpFixture(exchange -> reply(exchange, status.get(), "{}"))) {
            var service = server.service(Duration.ofSeconds(4), Runnable::run);
            awaitOperation(service, service::refresh);
            assertEquals(KitchenSync.Status.FAILED, service.status());
            assertTrue(service.message().contains("authentication"));
            status.set(403);
            awaitOperation(service, service::refresh);
            assertTrue(service.message().contains("denied"));
            assertFalse(service.writesAvailable());
            assertTrue(service.activeTickets().isEmpty());
        }
    }

    @Test
    void conflictsAndServerAllergyHoldsReloadAndNeverRepeatMutation() throws Exception {
        var reads = new AtomicInteger();
        var writes = new AtomicInteger();
        try (var server = new KitchenHttpFixture(exchange -> {
            if (exchange.getRequestMethod().equals("PATCH")) {
                String code = writes.incrementAndGet() == 1 ? "VERSION_CONFLICT" : "REVIEW_REQUIRED";
                reply(exchange, 409, "{\"code\":\"" + code + "\"}");
            } else {
                int read = reads.incrementAndGet();
                reply(exchange, 200, page(ticket("RECEIVED", read, read == 3), null));
            }
        })) {
            var service = server.service(Duration.ofSeconds(4), Runnable::run);
            awaitOperation(service, service::refresh);
            var first = service.activeTickets().getFirst();
            Runnable attempt = () -> service.advanceItem(first.id(), first.items().getFirst().id(), Status.RECEIVED);
            awaitOperation(service, attempt);
            assertEquals(2, service.activeTickets().getFirst().server().version());
            assertTrue(service.message().contains("version changed"));
            awaitOperation(service, attempt);
            assertTrue(service.message().contains("Allergy review"));
            assertFalse(service.activeTickets().getFirst().items().getFirst().actionable());
            assertEquals(2, writes.get());
            assertEquals(3, reads.get());
        }
    }

    @Test
    void reconcilesTimedOutPersistedWriteWithoutRetryAndPreservesSnapshotOnRefreshTimeout() throws Exception {
        var writes = new AtomicInteger();
        var state = new AtomicInteger();
        var stallReads = new java.util.concurrent.atomic.AtomicBoolean();
        try (var server = new KitchenHttpFixture(exchange -> {
            if (exchange.getRequestMethod().equals("PATCH")) {
                writes.incrementAndGet();
                state.set(1);
                pause();
            } else if (stallReads.get()) {
                pause();
            }
            int value = state.get();
            String result = exchange.getRequestMethod().equals("PATCH")
                    ? ticket("PREPARING", 2, false).toString()
                    : page(ticket(value == 0 ? "RECEIVED" : "PREPARING", value + 1, false), null);
            reply(exchange, 200, result);
        })) {
            var service = server.service(Duration.ofMillis(650), Runnable::run);
            awaitOperation(service, service::refresh);
            var first = service.activeTickets().getFirst();
            awaitOperation(service, () -> service.advanceItem(first.id(), first.items().getFirst().id(), Status.RECEIVED));
            assertEquals(1, writes.get());
            assertEquals(Status.PREPARING, service.activeTickets().getFirst().status());
            assertTrue(service.message().contains("confirms"));
            stallReads.set(true);
            awaitOperation(service, service::refresh);
            assertEquals(KitchenSync.Status.OFFLINE, service.status());
            assertEquals(Status.PREPARING, service.activeTickets().getFirst().status());
            assertFalse(service.writesAvailable());
            stallReads.set(false);
            awaitOperation(service, service::refresh);
            assertEquals(KitchenSync.Status.CONNECTED, service.status());
        }
    }

    @Test
    void failedLaterPageNeverReplacesTheLastCompleteSnapshot() throws Exception {
        var request = new AtomicInteger();
        try (var server = new KitchenHttpFixture(exchange -> {
            switch (request.incrementAndGet()) {
                case 1 -> reply(exchange, 200, page(ticket("RECEIVED", 1, false), null));
                case 2 -> reply(exchange, 200, page(ticket("PREPARING", 2, false), "next"));
                default -> reply(exchange, 503, "{\"code\":\"SERVICE_UNAVAILABLE\"}");
            }
        })) {
            var service = server.service(Duration.ofSeconds(4), Runnable::run);
            awaitOperation(service, service::refresh);
            var first = service.activeTickets();
            awaitOperation(service, service::refresh);
            assertSame(first, service.activeTickets());
            assertEquals(KitchenSync.Status.FAILED, service.status());
            assertFalse(service.writesAvailable());
        }
    }

    @Test
    void invalidTransitionRejectionDoesNotChangeLocalStateOrRetry() throws Exception {
        var writes = new AtomicInteger();
        try (var server = new KitchenHttpFixture(exchange -> {
            if (exchange.getRequestMethod().equals("PATCH")) {
                writes.incrementAndGet();
                reply(exchange, 422, "{\"code\":\"INVALID_TRANSITION\"}");
            } else {
                reply(exchange, 200, page(ticket("RECEIVED", 1, false), null));
            }
        })) {
            var service = server.service(Duration.ofSeconds(4), Runnable::run);
            awaitOperation(service, service::refresh);
            Ticket original = service.activeTickets().getFirst();
            awaitOperation(service, () -> service.advanceItem(original.id(), original.items().getFirst().id(), Status.RECEIVED));
            assertEquals(original, service.activeTickets().getFirst());
            assertTrue(service.message().contains("rejected the preparation transition"));
            assertEquals(1, writes.get());
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1400);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitOperation(SpringBootKitchenService service, Runnable operation) throws InterruptedException {
        var finished = new CountDownLatch(1);
        Runnable remove = service.onOrdersChanged(() -> {
            if (!service.busy()) {
                finished.countDown();
            }
        });
        try {
            operation.run();
            assertTrue(finished.await(7, TimeUnit.SECONDS), "REST operation did not finish");
        } finally {
            remove.run();
        }
    }
}
