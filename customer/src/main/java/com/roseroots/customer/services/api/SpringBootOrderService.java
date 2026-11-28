package com.roseroots.customer.services.api;

import static com.roseroots.customer.models.Ordering.canonicalId;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.OrderHistory;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.WaitEstimate;
import com.roseroots.customer.services.OrderService;
import com.roseroots.customer.services.WaitEstimateService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SpringBootOrderService implements OrderService, WaitEstimateService {
    private final HttpClient client;
    private final URI api;
    private final Supplier<String> credential;
    private final BooleanSupplier compatible;

    public SpringBootOrderService(URI api, Supplier<String> credential, BooleanSupplier compatible) {
        this(api, credential, compatible, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(12));
    }

    private final Duration requestTimeout;

    SpringBootOrderService(URI api, Supplier<String> credential, BooleanSupplier compatible,
                           HttpClient client, Duration requestTimeout) {
        if (!"https".equalsIgnoreCase(api.getScheme()) || api.getHost() == null || api.getUserInfo() != null
                || api.getQuery() != null || api.getFragment() != null || !api.getPath().endsWith("/api/v1/")) {
            throw new IllegalArgumentException("Expected a trusted HTTPS /api/v1/ base URI");
        }
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.api = api;
        this.credential = credential;
        this.compatible = compatible;
    }

    public SpringBootOrderService(BackendAddress address, Supplier<String> credential, BooleanSupplier compatible) {
        this(address.api(), credential, compatible);
    }

    /** Rebuild the current session view after subscribing or reconnecting. */
    public CompletionStage<OrderHistory> snapshot(UUID session) {
        String prefix = "sessions/" + session;
        return history(session, prefix + "/orders", null, new java.util.HashSet<>(), new java.util.ArrayList<>())
                .thenCombine(get(prefix + "/bill"), (orders, bill) -> {
                    return readHistory(session, orders, bill);
                });
    }

    static OrderHistory readHistory(UUID session, List<Ordering.Accepted> orders, JsonObject bill) {
        if (!session.equals(id(bill, "sessionId"))) throw new Problem("INVALID_RESPONSE", false);
        var orderTotals = new java.util.HashMap<UUID, Ordering.Totals>();
        for (var entry : bill.getAsJsonArray("orders")) {
            var row = entry.getAsJsonObject();
            if (orderTotals.putIfAbsent(id(row, "orderId"), totals(row.getAsJsonObject("totals"))) != null) {
                throw new Problem("INVALID_RESPONSE", false);
            }
        }
        return new OrderHistory(session, orders, orderTotals, totals(bill.getAsJsonObject("totals")), java.util.Map.of());
    }

    private CompletionStage<List<Ordering.Accepted>> history(UUID session, String path, String cursor,
            java.util.Set<String> seen, java.util.List<Ordering.Accepted> orders) {
        var suffix = cursor == null ? "" : "?cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8);
        return get(path + suffix).thenCompose(page -> {
            for (var order : list(page, "orders", SpringBootOrderService::readAccepted)) {
                if (!session.equals(order.sessionId())) throw new Problem("INVALID_RESPONSE", false);
                orders.add(order);
            }
            if (page.get("nextCursor").isJsonNull()) return java.util.concurrent.CompletableFuture.completedFuture(orders);
            String next = string(page, "nextCursor");
            if (next.isBlank() || next.length() > 500 || !seen.add(next)) throw new Problem("INVALID_RESPONSE", false);
            return history(session, path, next, seen, orders);
        });
    }

    CompletionStage<JsonObject> get(String path) {
        return exchange(request(path).GET().build(), false, false, Function.identity());
    }

    CompletionStage<JsonObject> postReview(UUID session, JsonObject body, UUID key) {
        return call("sessions/" + session + "/reviews", body, key, Function.identity());
    }

    CompletionStage<JsonObject> postFoodFeedback(UUID session, UUID order, UUID item, JsonObject body, UUID key) {
        String path = "sessions/" + session + "/orders/" + order + (item == null ? "" : "/items/" + item);
        return call(path + "/feedback", body, key, Function.identity());
    }

    CompletionStage<Optional<WaitEstimate>> estimateOrder(UUID order) {
        return get("orders/" + order + "/wait-estimate").thenApply(SpringBootOrderService::readEstimate);
    }

    CompletionStage<JsonObject> postAssistance(JsonObject body, UUID key) {
        return call("assistance", body, key, Function.identity());
    }

    CompletionStage<JsonObject> startSession(ServiceMode mode) {
        var body = new JsonObject();
        body.addProperty("mode", mode.name());
        var request = request("sessions").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return exchange(request, true, false, Function.identity());
    }

    public CompletionStage<Ordering.Accepted> order(UUID session, UUID orderId) {
        return get("orders/" + orderId).thenApply(body -> {
            var order = readAccepted(body);
            if (!orderId.equals(order.id()) || !session.equals(order.sessionId())) {
                throw new Problem("INVALID_RESPONSE", false);
            }
            return order;
        });
    }

    @Override public boolean available() { return compatible.getAsBoolean(); }

    @Override public boolean live() { return true; }

    @Override public CompletionStage<Optional<WaitEstimate>> estimate(Ordering.Draft draft) {
        return call("wait-estimates", draftBody(draft), null, SpringBootOrderService::readEstimate);
    }

    static Optional<WaitEstimate> readEstimate(JsonObject body) {
        if ("UNAVAILABLE".equals(string(body, "state"))) return Optional.empty();
        if (!"AVAILABLE".equals(string(body, "state"))) throw new Problem("INVALID_RESPONSE", false);
        return Optional.of(new WaitEstimate(
                estimateMinutes(body, "minimumMinutes"), estimateMinutes(body, "maximumMinutes"),
                Instant.parse(string(body, "calculatedAt")), Instant.parse(string(body, "validUntil"))));
    }

    private static Duration estimateMinutes(JsonObject body, String key) {
        var value = body.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new Problem("INVALID_RESPONSE", false);
        int minutes = value.getAsBigDecimal().intValueExact();
        if (minutes < 0 || minutes > 1440) throw new Problem("INVALID_RESPONSE", false);
        return Duration.ofMinutes(minutes);
    }

    @Override public CompletionStage<Ordering.Quote> quote(Ordering.Draft draft) {
        return call("order-quotes", draftBody(draft), null, SpringBootOrderService::readQuote);
    }

    @Override public CompletionStage<Ordering.Accepted> submit(Ordering.Draft draft, UUID quoteId, UUID key) {
        var body = draftBody(draft);
        body.addProperty("quoteId", quoteId.toString());
        body.addProperty("allergyReviewed", true);
        return call("orders", body, key, SpringBootOrderService::readAccepted);
    }

    <T> CompletionStage<T> call(String path, JsonObject body, UUID key, Function<JsonObject, T> read) {
        var request = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (key != null) request.header("Idempotency-Key", key.toString());
        return exchange(request.build(), key != null, key != null, read);
    }

    private HttpRequest.Builder request(String path) {
        if (!available()) throw new Problem("SERVICE_UNAVAILABLE", true);
        var token = credential.get();
        if (token == null || !token.matches("[A-Za-z0-9._~+/-]+=*")) throw new Problem("UNAUTHENTICATED", true);
        return HttpRequest.newBuilder(api.resolve(path)).timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, application/problem+json");
    }

    private <T> CompletionStage<T> exchange(HttpRequest request, boolean created, boolean submitting, Function<JsonObject, T> read) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle((reply, failure) -> {
            if (failure != null) throw new Problem("CONNECTION_INTERRUPTED", submitting);
            int status = reply.statusCode();
            var media = reply.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if ((status == 200 || (created && status == 201)) && media.equals("application/json")) {
                try {
                    return read.apply(JsonParser.parseString(reply.body()).getAsJsonObject());
                } catch (RuntimeException error) {
                    throw new Problem("INVALID_RESPONSE", submitting);
                }
            }
            throw responseProblem(status, media, reply.body(), submitting);
        });
    }

    static Problem responseProblem(int status, String media, String body, boolean submitting) {
        String code = "INVALID_RESPONSE";
        List<Violation> violations = List.of();
        if (media.equals("application/problem+json")) {
            try {
                var problem = JsonParser.parseString(body).getAsJsonObject();
                if (integer(problem, "status") == status) {
                    code = string(problem, "code");
                    if (problem.has("violations")) {
                        violations = list(problem, "violations", value -> new Violation(string(value, "pointer"),
                                string(value, "code"), string(value, "message")));
                    }
                    if (violations.isEmpty() && problem.has("detail")
                            && ("INVALID_SELECTION".equals(code) || "ITEM_UNAVAILABLE".equals(code) || "ALLERGEN_CONFLICT".equals(code))) {
                        violations = List.of(new Violation("", code, string(problem, "detail")));
                    }
                }
            } catch (RuntimeException ignored) {
                // A malformed error cannot establish whether an order was saved.
            }
        }
        boolean definite = switch (status) {
            case 409 -> List.of("PRICE_CHANGED", "QUOTE_EXPIRED", "ITEM_UNAVAILABLE",
                    "SESSION_CLOSED", "SESSION_EXPIRED", "INVALID_SELECTION", "IDEMPOTENCY_CONFLICT", "PAYMENT_PENDING",
                    "ALREADY_SETTLED").contains(code);
            case 422 -> List.of("ITEM_UNAVAILABLE", "INVALID_SELECTION", "REVIEW_REQUIRED", "ALLERGEN_CONFLICT").contains(code);
            default -> false;
        };
        return new Problem(code, submitting && !definite, violations);
    }

    static JsonObject draftBody(Ordering.Draft draft) {
        var body = new JsonObject();
        body.addProperty("sessionId", draft.sessionId().toString());
        body.add("visitAllergy", declaration(draft.visitAllergy()));
        var lines = new JsonArray();
        for (var line : draft.lines()) {
            var item = new JsonObject();
            item.addProperty("clientLineId", line.clientLineId().toString());
            item.addProperty("menuItemId", canonicalId(line.menuItemId()).toString());
            item.addProperty("quantity", line.quantity());
            var groups = new JsonArray();
            line.selections().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(group -> {
                if (group.getValue().isEmpty()) return;
                var entry = new JsonObject();
                entry.addProperty("groupId", canonicalId(group.getKey()).toString());
                var options = new JsonArray();
                group.getValue().stream().sorted().forEach(id -> options.add(canonicalId(id).toString()));
                entry.add("optionIds", options);
                groups.add(entry);
            });
            item.add("selections", groups);
            item.add("allergy", declaration(line.allergy()));
            item.addProperty("preparationInstructions", line.instructions());
            lines.add(item);
        }
        body.add("lines", lines);
        return body;
    }

    private static JsonObject declaration(AllergyDeclaration value) {
        var json = new JsonObject();
        json.addProperty("kind", value.disclosed() ? "DISCLOSED" : value.none() ? "NONE" : "UNREVIEWED");
        var allergens = new JsonArray();
        value.allergens().stream().sorted().forEach(allergen -> allergens.add(allergen.name()));
        json.add("allergens", allergens);
        json.addProperty("other", value.other());
        return json;
    }

    static Ordering.Quote readQuote(JsonObject json) {
        return new Ordering.Quote(id(json, "quoteId"), id(json, "sessionId"),
                Instant.parse(string(json, "expiresAt")), list(json, "lines", SpringBootOrderService::price),
                totals(json.getAsJsonObject("totals")));
    }

    static Ordering.Accepted readAccepted(JsonObject json) {
        return new Ordering.Accepted(id(json, "id"), id(json, "sessionId"),
                ServiceMode.valueOf(string(json, "mode")), Ordering.Status.valueOf(string(json, "status")),
                Instant.parse(string(json, "acceptedAt")), readDeclaration(json.getAsJsonObject("visitAllergy")),
                list(json, "lines", line -> new Ordering.AcceptedLine(id(line, "id"), price(line.getAsJsonObject("pricing")),
                        readDeclaration(line.getAsJsonObject("allergy")), string(line, "preparationInstructions"),
                        Ordering.Status.valueOf(string(line, "preparationStatus")),
                        Ordering.Review.valueOf(string(line, "allergyReviewStatus")),
                        bool(line, "billable"),
                        line.has("reviewMessage") && !line.get("reviewMessage").isJsonNull()
                                ? string(line, "reviewMessage") : "", version(line),
                        line.has("customerChangeAllowed") && bool(line, "customerChangeAllowed"),
                        line.has("customerChangeBlockedReason") && !line.get("customerChangeBlockedReason").isJsonNull()
                                ? string(line, "customerChangeBlockedReason") : "", feedback(line))),
                totals(json.getAsJsonObject("totals")), version(json), feedback(json));
    }

    private static Ordering.Feedback feedback(JsonObject json) {
        Integer rating = json.has("feedbackRating") && !json.get("feedbackRating").isJsonNull()
                ? json.getAsJsonPrimitive("feedbackRating").getAsBigDecimal().intValueExact() : null;
        if (rating != null && (rating < 1 || rating > 5)) throw new IllegalArgumentException("Invalid feedback rating");
        return new Ordering.Feedback(json.has("feedbackEligible") && bool(json, "feedbackEligible"), rating);
    }

    private static long version(JsonObject object) {
        return object.has("version") ? object.getAsJsonPrimitive("version").getAsBigDecimal().longValueExact() : 0;
    }

    private static AllergyDeclaration readDeclaration(JsonObject value) {
        var kind = string(value, "kind");
        var allergens = java.util.EnumSet.noneOf(AllergyDeclaration.Allergen.class);
        for (var allergen : value.getAsJsonArray("allergens")) {
            if (!allergens.add(AllergyDeclaration.Allergen.valueOf(allergen.getAsString()))) {
                throw new IllegalArgumentException("Duplicate declared allergen");
            }
        }
        var declaration = new AllergyDeclaration(kind.equals("NONE"), allergens, string(value, "other"));
        if (!kind.equals(string(declaration(declaration), "kind"))) {
            throw new IllegalArgumentException("Inconsistent allergy declaration");
        }
        return declaration;
    }

    static Ordering.Price price(JsonObject value) {
        return new Ordering.Price(id(value, "clientLineId"), id(value, "menuItemId"), string(value, "name"),
                Math.toIntExact(integer(value, "quantity")), integer(value, "baseUnitMinor"),
                list(value, "options", option -> new Ordering.Option(id(option, "groupId"), id(option, "optionId"),
                        string(option, "name"), integer(option, "unitAdjustmentMinor"))),
                integer(value, "extendedMinor"));
    }

    private static Ordering.Totals totals(JsonObject value) {
        return new Ordering.Totals(integer(value, "subtotalMinor"), integer(value, "serviceMinor"),
                integer(value, "taxMinor"), integer(value, "totalMinor"), string(value, "currency"));
    }

    private static boolean bool(JsonObject json, String key) {
        var value = json.getAsJsonPrimitive(key);
        if (!value.isBoolean()) throw new IllegalArgumentException("Expected JSON boolean");
        return value.getAsBoolean();
    }

    private static long integer(JsonObject json, String key) {
        var value = json.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new IllegalArgumentException("Expected JSON number");
        long amount = value.getAsBigDecimal().longValueExact();
        if (amount < -9007199254740991L || amount > 9007199254740991L) {
            throw new IllegalArgumentException("Outside JSON safe integer range");
        }
        return amount;
    }

    private static String string(JsonObject json, String key) {
        var value = json.getAsJsonPrimitive(key);
        if (!value.isString()) throw new IllegalArgumentException("Expected JSON string");
        return value.getAsString();
    }

    private static UUID id(JsonObject json, String key) { return canonicalId(string(json, key)); }



    private static <T> List<T> list(JsonObject json, String key, Function<JsonObject, T> read) {
        return json.getAsJsonArray(key).asList().stream().map(JsonElement::getAsJsonObject).map(read).toList();
    }
}
