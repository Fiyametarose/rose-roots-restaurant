package com.roseroots.kitchen.service.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class KitchenHttpFixture implements AutoCloseable {
    public static final String ITEM_ID = "00000000-0000-4000-8000-000000000011";
    public static final String ORDER_ID = "00000000-0000-4000-8000-000000000010";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public KitchenHttpFixture(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
    }

    public SpringBootKitchenService service(Duration timeout, Consumer<Runnable> dispatch) {
        return new SpringBootKitchenService(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                () -> Optional.of("fixture-token"), HttpClient.newHttpClient(), timeout, dispatch);
    }

    SpringBootKitchenService pollingService(Duration interval, Duration maxBackoff) {
        return new SpringBootKitchenService(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                () -> Optional.of("fixture-token"), HttpClient.newHttpClient(), Duration.ofSeconds(4), Runnable::run,
                interval, maxBackoff);
    }

    public static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try (exchange) {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    public static String page(JsonObject ticket, String cursor) {
        return "{\"tickets\":[" + ticket + "],\"nextCursor\":"
                + (cursor == null ? "null" : new com.google.gson.JsonPrimitive(cursor)) + "}";
    }

    public static JsonObject ticket(String status, long version, boolean hold) {
        String template = """
                {"serviceDestination":"Table 07","priority":"RUSH","delayed":true,"order":{
                "id":"%s","sessionId":"00000000-0000-4000-8000-000000000020","mode":"DINE_IN",
                "acceptedAt":"2026-11-20T18:01:00Z","status":"%s","version":%d,
                "visitAllergy":{"kind":"NONE","allergens":[],"other":""},
                "lines":[{"id":"%s","preparationStatus":"%s","version":%d,
                "pricing":{"clientLineId":"00000000-0000-4000-8000-000000000012",
                "menuItemId":"00000000-0000-4000-8000-000000000013","quantity":2,"name":"Doro Wat",
                "baseUnitMinor":125000,"extendedMinor":250000,
                "options":[{"groupId":"00000000-0000-4000-8000-000000000014",
                "optionId":"00000000-0000-4000-8000-000000000015","name":"Sauce on side","unitAdjustmentMinor":0}]},
                "preparationInstructions":"Pack injera separately","allergy":{"kind":"NONE","allergens":[],"other":""},
                "allergyReviewStatus":"%s","allergyRisk":{"ingredientMatches":[],"crossContactMatches":["WHEAT"],"unresolved":%s},
                "billable":true,"activeSubstitution":null}],
                "totals":{"currency":"KES","subtotalMinor":250000,"serviceMinor":0,"taxMinor":0,"totalMinor":250000}}}
                """;
        return KitchenTicketJson.object(template.formatted(ORDER_ID, status, version, ITEM_ID, status, version,
                hold ? "PENDING_ALLERGY_REVIEW" : "NOT_REQUIRED", hold));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
