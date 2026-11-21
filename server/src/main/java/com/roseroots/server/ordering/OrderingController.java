package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.roseroots.server.admin.ReviewSource.FollowUp;
import com.roseroots.server.admin.ReviewSource.Topic;
import java.security.Principal;
import java.util.EnumSet;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@RestController
@RequestMapping("/api/v1")
@Profile("catalog")
class OrderingController {
    private static final String JSON_MEDIA = MediaType.APPLICATION_JSON_VALUE;
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private final OrderingService ordering;
    private final AssistanceRequests assistance;
    private final VisitReviews reviews;

    OrderingController(OrderingService ordering, AssistanceRequests assistance, VisitReviews reviews) {
        this.ordering = ordering;
        this.assistance = assistance;
        this.reviews = reviews;
    }

    @GetMapping("/device")
    ResponseEntity<String> device(@AuthenticationPrincipal Actor actor) {
        return response(200, ordering.device(actor));
    }

    @PostMapping(path = "/sessions", consumes = JSON_MEDIA)
    ResponseEntity<String> start(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        var body = fields(body(request), "mode");
        return response(ordering.start(actor, choice(body, "mode", "DINE_IN", "TAKEAWAY")));
    }

    @GetMapping("/sessions/{id}")
    ResponseEntity<String> session(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session) {
        return response(200, ordering.session(actor, id(session)));
    }

    @PostMapping(path = "/order-quotes", consumes = JSON_MEDIA)
    ResponseEntity<String> quote(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        var body = fields(body(request), "sessionId", "visitAllergy", "lines");
        return response(200, ordering.quote(actor, OrderingPricing.draft(body)));
    }

    @PostMapping(path = "/orders", consumes = JSON_MEDIA)
    ResponseEntity<String> submit(@AuthenticationPrincipal Actor actor, HttpServletRequest request,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        var body = fields(body(request), "sessionId", "quoteId", "visitAllergy", "lines", "allergyReviewed");
        return response(ordering.submit(actor, id(key), body));
    }

    @GetMapping("/orders/{id}")
    ResponseEntity<String> order(@AuthenticationPrincipal Actor actor, @PathVariable("id") String order) {
        return response(200, ordering.order(actor, id(order)));
    }

    @GetMapping("/sessions/{id}/orders")
    ResponseEntity<String> history(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session,
                                   @RequestParam(name = "cursor", required = false) String cursor,
                                   @RequestParam(name = "limit", required = false) String limit) {
        return response(200, ordering.history(actor, id(session), cursor, limit(limit)));
    }

    @GetMapping("/sessions/{id}/bill")
    ResponseEntity<String> bill(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session) {
        return response(200, ordering.bill(actor, id(session)));
    }

    @GetMapping("/kitchen/orders")
    ResponseEntity<String> kitchen(@AuthenticationPrincipal Actor actor,
                                   @RequestParam(name = "cursor", required = false) String cursor,
                                   @RequestParam(name = "limit", required = false) String limit) {
        return response(200, ordering.kitchen(actor, cursor, limit(limit)));
    }

    @PatchMapping(path = "/kitchen/items/{id}/status", consumes = JSON_MEDIA)
    ResponseEntity<String> transition(@AuthenticationPrincipal Actor actor, @PathVariable("id") String item,
                                      HttpServletRequest request) {
        var body = fields(body(request), "status", "expectedVersion");
        return response(200, ordering.transition(actor, id(item),
                choice(body, "status", "PREPARING", "READY", "SERVED", "COLLECTED"),
                integer(body, "expectedVersion", 1, MAX_SAFE)));
    }

    @PostMapping(path = "/kitchen/orders/{id}/handoff", consumes = JSON_MEDIA)
    ResponseEntity<String> handoff(@AuthenticationPrincipal Actor actor, @PathVariable("id") String order,
                                   HttpServletRequest request) {
        var input = fields(body(request), "status", "expectedVersion");
        return response(200, ordering.handoff(actor, id(order), choice(input, "status", "SERVED", "COLLECTED"),
                integer(input, "expectedVersion", 1, MAX_SAFE)));
    }

    @PostMapping(path = "/kitchen/items/{id}/allergy-review", consumes = JSON_MEDIA)
    ResponseEntity<String> review(@AuthenticationPrincipal Actor actor, @PathVariable("id") String item,
                                  HttpServletRequest request) {
        var body = fields(body(request), "decision", "reason", "expectedVersion");
        String reason = text(body, "reason", 240).strip();
        if (reason.isBlank()) {
            throw OrderingProblem.bad("A review reason is required.");
        }
        return response(200, ordering.review(actor, id(item), choice(body, "decision", "APPROVE", "DECLINE"),
                reason, integer(body, "expectedVersion", 1, MAX_SAFE)));
    }

    @ExceptionHandler(OrderingProblem.class)
    ResponseEntity<String> problem(OrderingProblem error) {
        return ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(error.body().toString());
    }

    @PostMapping(path = "/assistance", consumes = JSON_MEDIA)
    ResponseEntity<String> submitAssistance(@AuthenticationPrincipal Actor actor, HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        JsonNode input = body(request);
        boolean noteProvided = input != null && input.has("note");
        fields(input, noteProvided ? new String[]{"sessionId", "type", "note"} : new String[]{"sessionId", "type"});
        return response(assistance.submit(actor, id(input, "sessionId"), id(key),
                choice(input, "type", "WAITER", "DRINKING_WATER", "CUTLERY", "NAPKINS", "ORDER_HELP", "BILL", "OTHER"),
                noteProvided ? text(input, "note", 240).strip() : ""));
    }

    @GetMapping("/sessions/{id}/assistance")
    ResponseEntity<String> sessionAssistance(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return response(200, assistance.page(actor, id(session), cursor, limit(limit)));
    }

    @GetMapping("/kitchen/assistance")
    ResponseEntity<String> kitchenAssistance(@AuthenticationPrincipal Actor actor,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return response(200, assistance.page(actor, null, cursor, limit(limit)));
    }

    @GetMapping("/sessions/{id}/review")
    ResponseEntity<String> reviewEligibility(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session) {
        return response(200, reviews.eligibility(actor, id(session)));
    }

    @PostMapping(path = "/sessions/{id}/reviews", consumes = JSON_MEDIA)
    ResponseEntity<String> submitReview(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        JsonNode input = body(request);
        Set<String> allowed = Set.of("rating", "topics", "comment");
        if (input == null || !input.isObject()) throw OrderingProblem.bad("Use a review object.");
        input.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw OrderingProblem.bad("Unexpected review field.");
        });
        Integer rating = input.hasNonNull("rating") ? (int) integer(input, "rating", 1, 5) : null;
        var topics = EnumSet.noneOf(Topic.class);
        if (input.has("topics")) {
            for (JsonNode value : list(input, "topics", 0, 8)) {
                try {
                    if (!value.isTextual() || !topics.add(Topic.valueOf(value.textValue()))) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) {
                    throw OrderingProblem.bad("Use distinct review topics from the contract.");
                }
            }
        }
        String comment = input.has("comment") ? VisitReviews.plain(text(input, "comment", 500)) : "";
        if (rating == null && topics.isEmpty() && comment.isEmpty()) {
            throw new OrderingProblem(422, "INVALID_SELECTION", "Provide a rating, topic, or comment.");
        }
        return response(reviews.submit(actor, id(session), id(key), new VisitReviews.Submission(rating, topics, comment)));
    }

    @GetMapping("/kitchen/reviews")
    ResponseEntity<String> kitchenReviews(@AuthenticationPrincipal Actor actor,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return response(200, reviews.kitchen(actor, cursor, limit(limit)));
    }

    @PatchMapping(path = "/admin/reviews/{id}/follow-up", consumes = JSON_MEDIA)
    ResponseEntity<String> followUp(Principal manager, @PathVariable("id") String review, HttpServletRequest request) {
        JsonNode input = fields(body(request), "followUp", "internalResponse", "expectedVersion");
        var next = FollowUp.valueOf(choice(input, "followUp", "NEEDS_ATTENTION", "FOLLOWED_UP", "RESOLVED"));
        return response(200, reviews.followUp(id(review), manager.getName(), next,
                VisitReviews.plain(text(input, "internalResponse", 500)), integer(input, "expectedVersion", 1, MAX_SAFE)));
    }

    @PatchMapping(path = "/kitchen/assistance/{id}", consumes = JSON_MEDIA)
    ResponseEntity<String> updateAssistance(@AuthenticationPrincipal Actor actor, @PathVariable("id") String requestId,
            HttpServletRequest request) {
        var input = fields(body(request), "status", "expectedVersion");
        return response(200, assistance.transition(actor, id(requestId),
                choice(input, "status", "ACKNOWLEDGED", "IN_PROGRESS", "COMPLETED"),
                integer(input, "expectedVersion", 1, MAX_SAFE)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<String> storageProblem() {
        return problem(new OrderingProblem(503, "SERVICE_UNAVAILABLE", "Ordering storage is unavailable."));
    }

    static int limit(String value) {
        if (value == null) {
            return 50;
        }
        if (value.matches("[0-9]{1,3}")) {
            int limit = Integer.parseInt(value);
            if (limit >= 1 && limit <= 200) {
                return limit;
            }
        }
        throw OrderingProblem.bad("Limit must be between 1 and 200.");
    }

    static JsonNode body(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length <= MAX_REQUEST_BYTES) {
                return parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            }
        } catch (IOException ignored) {
            // Normalize request errors without echoing sensitive notes.
        }
        throw OrderingProblem.bad("Invalid ordering request body.");
    }

    static ResponseEntity<String> response(OrderingService.Result result) {
        return response(result.status(), result.body());
    }

    static ResponseEntity<String> response(int status, JsonNode node) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(node.toString());
    }
}
