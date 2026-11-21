package com.roseroots.customer.services.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.services.OrderService;
import com.roseroots.customer.state.ReviewState.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Uses the integrated draft.3 session-scoped Customer Reviews operations. */
public final class RestReviewClient implements Submission {
    private final SpringBootOrderService rest;

    public RestReviewClient(SpringBootOrderService rest) { this.rest = rest; }

    @Override public boolean available() { return rest.available(); }

    @Override public CompletionStage<Eligibility> eligibility(UUID session) {
        return rest.get("sessions/" + session + "/review").thenApply(body -> {
            var allowed = body.getAsJsonPrimitive("eligible");
            if (!allowed.isBoolean()) throw new IllegalArgumentException("Invalid review eligibility");
            var existing = body.get("review");
            return new Eligibility(allowed.getAsBoolean(), existing.isJsonNull() ? null
                    : receipt(session, existing.getAsJsonObject()));
        });
    }

    @Override public CompletionStage<Receipt> send(Attempt attempt) {
        return rest.postReview(attempt.sessionId(), body(attempt.draft()), attempt.key()).handle((response, failure) -> {
            if (failure == null) return receipt(attempt.sessionId(), response);
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof OrderService.Problem problem) {
                switch (problem.code()) {
                    case "REVIEW_ALREADY_EXISTS" -> throw new AlreadyExists();
                    case "REVIEW_NOT_ELIGIBLE" -> throw new NotEligible();
                    default -> {
                        if (Set.of("BAD_REQUEST", "INVALID_SELECTION", "IDEMPOTENCY_CONFLICT").contains(problem.code())) {
                            throw new Rejected();
                        }
                    }
                }
            }
            throw new CompletionException(failure);
        });
    }

    static JsonObject body(Draft draft) {
        var body = new JsonObject();
        if (draft.stars() == null) body.add("rating", JsonNull.INSTANCE);
        else body.addProperty("rating", draft.stars());
        var topics = new JsonArray();
        draft.topics().stream().sorted().forEach(topic -> topics.add(topic.name()));
        body.add("topics", topics);
        body.addProperty("comment", draft.comment().strip());
        return body;
    }

    private static Receipt receipt(UUID session, JsonObject body) {
        var returned = Ordering.canonicalId(body.get("sessionId").getAsString());
        if (!session.equals(returned)) throw new IllegalArgumentException("Review belongs to another session");
        Instant.parse(body.get("submittedAt").getAsString());
        return new Receipt(returned, Ordering.canonicalId(body.get("id").getAsString()));
    }
}
