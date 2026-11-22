package com.roseroots.customer.services;

import com.roseroots.customer.models.WaitEstimate;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface WaitEstimateService {
    default boolean live() { return false; }
    /** Spring Boot supplies a range from current staffing, station workload, and preparation priorities. */
    CompletionStage<Optional<WaitEstimate>> estimate(WaitEstimate.Request request);

    static WaitEstimateService unavailable() {
        return request -> CompletableFuture.completedFuture(Optional.empty());
    }

    static Optional<WaitEstimate> current(Optional<WaitEstimate> response, Instant now) {
        return response.filter(estimate -> estimate.isCurrent(now));
    }
}
