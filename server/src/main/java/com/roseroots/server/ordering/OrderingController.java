package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.roseroots.server.admin.ReviewSource.FollowUp;
import com.roseroots.server.admin.ReviewSource.Topic;
import java.security.Principal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final WaitEstimates estimates;
    private final OrderFeedback feedback;

    OrderingController(OrderingService ordering, AssistanceRequests assistance, VisitReviews reviews,
                       WaitEstimates estimates, OrderFeedback feedback) {
        this.ordering = ordering;
        this.assistance = assistance;
        this.reviews = reviews;
        this.estimates = estimates;
        this.feedback = feedback;
    }

    @PostMapping(path = "/wait-estimates", consumes = JSON_MEDIA)
    ResponseEntity<String> estimateDraft(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        var input = body(request);
        if (input.isObject() && !input.has("visitAllergy")) {
            fields(input, "sessionId", "lines");
            ((com.fasterxml.jackson.databind.node.ObjectNode) input).set("visitAllergy",
                    object().put("kind", "UNREVIEWED").put("other", "").set("allergens", array()));
        }
        return response(200, estimates.draft(actor, OrderingPricing.draft(fields(input, "sessionId", "lines", "visitAllergy"))));
    }

    @GetMapping("/orders/{id}/wait-estimate")
    ResponseEntity<String> estimateAccepted(@AuthenticationPrincipal Actor actor, @PathVariable("id") String order) {
        return response(200, estimates.accepted(actor, id(order)));
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

    @GetMapping("/kitchen/history")
    ResponseEntity<String> kitchenHistory(@AuthenticationPrincipal Actor actor,
                                          @RequestParam(name = "cursor", required = false) String cursor,
                                          @RequestParam(name = "limit", required = false) String limit) {
        return response(200, ordering.kitchenHistory(actor, cursor, limit(limit)));
    }

    @PatchMapping(path = "/kitchen/items/{id}/status", consumes = JSON_MEDIA)
    ResponseEntity<String> transition(@AuthenticationPrincipal Actor actor, @PathVariable("id") String item,
                                      HttpServletRequest request) {
        var body = fields(body(request), "status", "expectedVersion");
        return response(200, ordering.transition(actor, id(item),
                choice(body, "status", "PREPARING", "READY", "SERVED", "COLLECTED"),
                integer(body, "expectedVersion", 1, MAX_SAFE)));
    }

    @PostMapping(path = "/kitchen/items/{id}/ownership", consumes = JSON_MEDIA)
    ResponseEntity<String> ownership(@AuthenticationPrincipal Actor actor, @PathVariable("id") String item,
                                     HttpServletRequest request) {
        var input = body(request);
        String action = choice(input, "action", "CLAIMED", "RELEASED", "REASSIGNED");
        boolean reassign = action.equals("REASSIGNED");
        if (reassign) fields(input, "action", "expectedVersion", "chefId", "reason");
        else fields(input, "action", "expectedVersion");
        String reason = reassign ? text(input, "reason", 240).strip() : null;
        if (reassign && (reason.isBlank() || reason.chars().anyMatch(Character::isISOControl))) {
            throw OrderingProblem.bad("Enter a reason for reassignment.");
        }
        return response(200, ordering.changeOwnership(actor, id(item), action, reassign ? id(input, "chefId") : null,
                reason, integer(input, "expectedVersion", 1, MAX_SAFE)));
    }

    @GetMapping("/kitchen/items/{id}/preparation-history")
    ResponseEntity<String> preparationHistory(@AuthenticationPrincipal Actor actor, @PathVariable("id") String item) {
        return response(200, ordering.preparationHistory(actor, id(item)));
    }

    @GetMapping("/kitchen/items/{id}/eligible-chefs")
    ResponseEntity<String> eligibleChefs(@AuthenticationPrincipal Actor actor, @PathVariable("id") String item) {
        return response(200, ordering.eligibleChefs(actor, id(item)));
    }

    @PostMapping(path = "/kitchen/orders/{id}/handoff", consumes = JSON_MEDIA)
    ResponseEntity<String> handoff(@AuthenticationPrincipal Actor actor, @PathVariable("id") String order,
                                   HttpServletRequest request) {
        var input = fields(body(request), "status", "expectedVersion");
        return response(200, ordering.handoff(actor, id(order), choice(input, "status", "SERVED", "COLLECTED"),
                integer(input, "expectedVersion", 1, MAX_SAFE)));
    }

    @PostMapping(path = {"/kitchen/orders/{id}/rush", "/kitchen/orders/{id}/delayed"}, consumes = JSON_MEDIA)
    ResponseEntity<String> kitchenPriority(@AuthenticationPrincipal Actor actor, @PathVariable("id") String order,
                                           HttpServletRequest request) {
        return priority(actor, null, order, request);
    }

    @PostMapping(path = {"/admin/orders/{id}/rush", "/admin/orders/{id}/delayed"}, consumes = JSON_MEDIA)
    ResponseEntity<String> managerPriority(Principal actor, @PathVariable("id") String order, HttpServletRequest request) {
        return priority(null, actor.getName(), order, request);
    }

    @GetMapping("/admin/kitchen/priority")
    ResponseEntity<String> managerPriorityQueue(@RequestParam(name = "cursor", required = false) String cursor) {
        return response(200, ordering.managerKitchen(cursor));
    }

    private ResponseEntity<String> priority(Actor actor, String manager, String order, HttpServletRequest request) {
        boolean rush = request.getRequestURI().endsWith("/rush");
        var input = fields(body(request), rush ? "priority" : "delayed", "reason", "expectedVersion");
        if (!rush && !input.get("delayed").isBoolean()) throw OrderingProblem.bad("The delayed field must be boolean.");
        boolean enabled = rush ? choice(input, "priority", "RUSH", "NORMAL").equals("RUSH") : input.get("delayed").booleanValue();
        String reason = text(input, "reason", 240).strip();
        if (reason.isEmpty()) throw OrderingProblem.bad("A priority change reason is required.");
        return response(200, ordering.changePriority(actor, manager, id(order), rush, enabled, reason,
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

    @PostMapping(path="/sessions/{session}/orders/{order}/items/{item}/preview", consumes=JSON_MEDIA)
    ResponseEntity<String> previewItem(@AuthenticationPrincipal Actor actor, @PathVariable("session") String session,
            @PathVariable("order") String order, @PathVariable("item") String item, HttpServletRequest request) {
        var input = fields(body(request), "expectedItemVersion", "expectedOrderVersion", "replacement");
        return response(200, ordering.previewItem(actor, id(session), id(order), id(item),
                integer(input,"expectedItemVersion",1,MAX_SAFE), integer(input,"expectedOrderVersion",1,MAX_SAFE),
                input.get("replacement")));
    }

    @PostMapping(path="/sessions/{session}/orders/{order}/items/{item}/edit", consumes=JSON_MEDIA)
    ResponseEntity<String> editItem(@AuthenticationPrincipal Actor actor, @PathVariable("session") String session,
            @PathVariable("order") String order, @PathVariable("item") String item,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var input = fields(body(request), "expectedItemVersion", "expectedOrderVersion", "replacement", "fingerprint");
        return response(200, ordering.changeCustomerItem(actor,id(session),id(order),id(item),id(key),
                integer(input,"expectedItemVersion",1,MAX_SAFE), integer(input,"expectedOrderVersion",1,MAX_SAFE),
                input.get("replacement"),text(input,"fingerprint",64)));
    }

    @PostMapping(path="/sessions/{session}/orders/{order}/items/{item}/cancel", consumes=JSON_MEDIA)
    ResponseEntity<String> cancelItem(@AuthenticationPrincipal Actor actor, @PathVariable("session") String session,
            @PathVariable("order") String order, @PathVariable("item") String item,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var input = fields(body(request), "expectedItemVersion", "expectedOrderVersion");
        return response(200, ordering.changeCustomerItem(actor,id(session),id(order),id(item),id(key),
                integer(input,"expectedItemVersion",1,MAX_SAFE), integer(input,"expectedOrderVersion",1,MAX_SAFE), null,null));
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

    @PostMapping(path = {"/sessions/{session}/orders/{order}/feedback",
            "/sessions/{session}/orders/{order}/items/{item}/feedback"}, consumes = JSON_MEDIA)
    ResponseEntity<String> submitOrderFeedback(@AuthenticationPrincipal Actor actor,
            @PathVariable("session") String session, @PathVariable("order") String order,
            @PathVariable(name = "item", required = false) String item,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return response(feedback.submit(actor, id(session), id(order), item == null ? null : id(item), id(key), body(request)));
    }

    @GetMapping("/kitchen/food-feedback")
    ResponseEntity<String> kitchenFeedback(@AuthenticationPrincipal Actor actor,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        return response(200, feedback.report(actor.restaurantId(), "ALL", "", null, null, null, offset, limit));
    }

    @GetMapping("/admin/food-feedback")
    ResponseEntity<String> managerFeedback(@RequestParam(name = "type", defaultValue = "ALL") String type,
            @RequestParam(name = "dish", defaultValue = "") String dish,
            @RequestParam(name = "rating", required = false) Integer rating,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        return response(200, feedback.report(null, type, dish, rating, from, to, offset, limit));
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

    @GetMapping("/kitchen/assistance/{id}/eligible-waiters")
    ResponseEntity<String> eligibleWaiters(@AuthenticationPrincipal Actor actor, @PathVariable("id") String requestId) {
        return response(200, assistance.eligibleWaiters(actor, id(requestId)));
    }

    @PostMapping(path = "/kitchen/assistance/{id}/reassign", consumes = JSON_MEDIA)
    ResponseEntity<String> reassignAssistance(@AuthenticationPrincipal Actor actor, @PathVariable("id") String requestId,
            HttpServletRequest request) {
        var input = fields(body(request), "waiterId", "expectedVersion", "reason");
        String why = text(input, "reason", 240).strip();
        if (why.isEmpty()) throw OrderingProblem.bad("Enter a reassignment reason.");
        return response(200, assistance.reassign(actor, id(requestId), id(input, "waiterId"),
                integer(input, "expectedVersion", 1, MAX_SAFE), why));
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
