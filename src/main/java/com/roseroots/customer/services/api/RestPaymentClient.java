package com.roseroots.customer.services.api;

import com.google.gson.JsonObject;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.Payment;
import com.roseroots.customer.services.PaymentService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class RestPaymentClient implements PaymentService {
    private final SpringBootOrderService rest;
    public RestPaymentClient(SpringBootOrderService rest) { this.rest = rest; }

    @Override public CompletionStage<Payment.Balance> balance(UUID session) {
        return rest.get(path(session) + "payable-balance").thenApply(json -> {
            verify(json, session, false);
            long payable = amount(json, "payableMinor", 0);
            long settled = amount(json, "settledMinor", 0);
            long outstanding = amount(json, "outstandingMinor", 0);
            if (payable - settled != outstanding) throw invalid();
            return new Payment.Balance(session, amount(json, "billVersion", 1), payable, settled, outstanding,
                    json.getAsJsonArray("blockedReasons").asList().stream().map(value -> value.getAsString())
                            .collect(Collectors.toUnmodifiableSet()),
                    optionalId(json, "openAttemptId"), json.getAsJsonArray("availableMethods").asList().stream()
                            .anyMatch(value -> "CASH".equals(value.getAsString())));
        });
    }

    @Override public CompletionStage<Payment.Attempt> start(UUID session, long version, UUID key) {
        var body = new JsonObject();
        body.addProperty("expectedBillVersion", version);
        body.addProperty("method", "CASH");
        return rest.call(path(session) + "payments", body, key, json -> {
            var attempt = readAttempt(json, session, null);
            if (attempt.billVersion() != version) throw invalid();
            return attempt;
        });
    }

    @Override public CompletionStage<Payment.Attempt> attempt(UUID session, UUID id) {
        return rest.get(path(session) + "payments/" + id).thenApply(json -> readAttempt(json, session, id));
    }

    @Override public CompletionStage<Payment.Attempt> cancel(UUID session, UUID id, long version) {
        var body = new JsonObject();
        body.addProperty("expectedVersion", version);
        return rest.call(path(session) + "payments/" + id + "/cancel", body, null,
                json -> readAttempt(json, session, id));
    }

    @Override public CompletionStage<Payment.Receipt> receipt(UUID session, UUID id) {
        return rest.get(path(session) + "receipts/" + id).thenApply(json -> {
            verify(json, session, true);
            if (!id.equals(id(json, "id"))) throw invalid();
            var lines = json.getAsJsonArray("lines").asList().stream().map(value -> {
                var line = value.getAsJsonObject();
                return new Payment.Line(id(line, "orderId"), id(line, "itemId"),
                        SpringBootOrderService.price(line.getAsJsonObject("pricing")));
            }).toList();
            return new Payment.Receipt(id, session, id(json, "paymentId"), amount(json, "amountMinor", 0),
                    Instant.parse(json.get("verifiedAt").getAsString()), lines);
        });
    }

    private static Payment.Attempt readAttempt(JsonObject json, UUID session, UUID expected) {
        verify(json, session, true);
        var id = id(json, "id");
        var status = Payment.Status.valueOf(json.get("status").getAsString());
        var receipt = optionalId(json, "receiptId");
        if ((expected != null && !expected.equals(id)) || (status == Payment.Status.SUCCEEDED) != (receipt != null)) {
            throw invalid();
        }
        return new Payment.Attempt(id, session, status, amount(json, "billVersion", 1),
                amount(json, "version", 1), amount(json, "amountMinor", 0),
                json.get("cancellationRequested").getAsBoolean(), receipt);
    }

    private static void verify(JsonObject json, UUID session, boolean cash) {
        if (!session.equals(id(json, "sessionId")) || !"KES".equals(json.get("currency").getAsString())
                || cash && !"CASH".equals(json.get("method").getAsString())) throw invalid();
    }

    private static String path(UUID session) { return "sessions/" + session + "/"; }
    private static UUID id(JsonObject values, String key) { return Ordering.canonicalId(values.get(key).getAsString()); }
    private static UUID optionalId(JsonObject values, String key) {
        return values.get(key).isJsonNull() ? null : id(values, key);
    }

    private static long amount(JsonObject values, String key, long minimum) {
        var value = values.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw invalid();
        long number = value.getAsBigDecimal().longValueExact();
        if (number < minimum || number > 9_007_199_254_740_991L) throw invalid();
        return number;
    }

    private static com.roseroots.customer.services.OrderService.Problem invalid() {
        return new com.roseroots.customer.services.OrderService.Problem("INVALID_RESPONSE", false);
    }
}
