package com.roseroots.customer.services.api;

import com.google.gson.JsonObject;
import com.roseroots.customer.models.WaitEstimate;
import com.roseroots.customer.services.WaitEstimateService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RestWaitEstimates implements WaitEstimateService {
    private final SpringBootOrderService orders;

    public RestWaitEstimates(SpringBootOrderService orders) { this.orders = orders; }

    @Override public boolean live() { return true; }

    @Override public CompletionStage<Optional<WaitEstimate>> estimate(WaitEstimate.Request request) {
        var draft = request.wireDraft();
        if (draft == null || !draft.sessionId().toString().equals(request.sessionId())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return orders.estimateDraft(draft);
    }

    static Optional<WaitEstimate> read(JsonObject body) {
        String state = body.get("state").getAsString();
        if (state.equals("UNAVAILABLE")) return Optional.empty();
        if (!state.equals("AVAILABLE")) throw new IllegalArgumentException("Unknown estimate state");
        var low = Duration.ofMinutes(body.get("minimumMinutes").getAsBigDecimal().longValueExact());
        var high = Duration.ofMinutes(body.get("maximumMinutes").getAsBigDecimal().longValueExact());
        if (high.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("Invalid estimate range");
        return Optional.of(new WaitEstimate(low, high, Instant.parse(body.get("calculatedAt").getAsString()),
                Instant.parse(body.get("validUntil").getAsString())));
    }
}
