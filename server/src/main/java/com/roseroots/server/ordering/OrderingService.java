package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingProblem.conflict;
import static com.roseroots.server.ordering.OrderingProblem.missing;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@Service
@Profile("catalog")
class OrderingService {
    record Result(int status, JsonNode body) {}

    private record Device(UUID id, UUID tableId, String tableNumber, long version) {}
    private record Session(UUID id, String mode, UUID tableId, String tableNumber, String pickup, String status,
                           Instant createdAt, Instant expiresAt, Instant closedAt) {
        String currentStatus(Instant now) {
            return status.equals("OPEN") && !expiresAt.isAfter(now) ? "EXPIRED" : status;
        }

        void requireOpen(Instant now) {
            switch (currentStatus(now)) {
                case "EXPIRED" -> throw conflict("SESSION_EXPIRED", "The dining session expired.");
                case "CLOSED" -> throw conflict("SESSION_CLOSED", "The dining session is closed.");
                default -> { }
            }
        }
    }

    private record Quote(Instant expiresAt, JsonNode draft, JsonNode pricing) {}
    private record Submission(byte[] fingerprint, JsonNode response) {}
    private record Cursor(long after, long through) {}

    private final JdbcClient jdbc;
    private final OrderingPricing pricing;

    OrderingService(JdbcClient jdbc, OrderingPricing pricing) {
        this.jdbc = jdbc;
        this.pricing = pricing;
    }

    ObjectNode device(Actor actor) {
        Device device = device(actor, false);
        return object().put("id", device.id().toString()).put("version", device.version())
                .set("table", table(device.tableId(), device.tableNumber()));
    }

    @Transactional
    public Result start(Actor actor, String mode) {
        Device device = device(actor, true);
        Instant now = now();
        jdbc.sql("""
                UPDATE dining_session SET status = 'EXPIRED'
                WHERE terminal_id = :terminal AND status = 'OPEN' AND expires_at <= :now
                """).param("terminal", device.id()).param("now", utc(now)).update();
        var existing = jdbc.sql("SELECT * FROM dining_session WHERE terminal_id = :terminal AND status = 'OPEN'")
                .param("terminal", device.id()).query(OrderingService::sessionRow).optional();
        if (existing.isPresent()) {
            var session = existing.get();
            if (!session.mode().equals(mode)) {
                throw conflict("SESSION_MODE_CONFLICT", "This terminal already has an open session in another mode.");
            }
            return new Result(200, sessionJson(session, now));
        }
        UUID id = UUID.randomUUID();
        boolean dineIn = mode.equals("DINE_IN");
        if (dineIn && device.tableId() == null) {
            throw conflict("DEVICE_UNASSIGNED", "This terminal has no authorized table assignment.");
        }
        var session = new Session(id, mode, dineIn ? device.tableId() : null, dineIn ? device.tableNumber() : null,
                dineIn ? null : "P-" + id, "OPEN", now, now.plus(8, ChronoUnit.HOURS), null);
        jdbc.sql("""
                INSERT INTO dining_session (id, restaurant_id, terminal_id, mode, table_id, table_number,
                    pickup_reference, created_at, expires_at, status)
                VALUES (:id, :restaurant, :terminal, :mode, :table, :number, :pickup, :created, :expires, 'OPEN')
                """).param("id", id).param("restaurant", actor.restaurantId()).param("terminal", device.id())
                .param("mode", mode).param("table", session.tableId()).param("number", session.tableNumber())
                .param("pickup", session.pickup()).param("created", utc(session.createdAt()))
                .param("expires", utc(session.expiresAt())).update();
        return new Result(201, sessionJson(session, now));
    }

    ObjectNode session(Actor actor, UUID id) { return sessionJson(ownedSession(actor, id, false), now()); }

    @Transactional
    public ObjectNode quote(Actor actor, ObjectNode draft) {
        UUID sessionId = id(draft, "sessionId");
        var session = ownedSession(actor, sessionId, true);
        session.requireOpen(now());
        ObjectNode snapshot = pricing.price(draft).snapshot();
        Instant now = now();
        session.requireOpen(now);
        UUID quoteId = UUID.randomUUID();
        Instant expires = now.plus(5, ChronoUnit.MINUTES);
        jdbc.sql("""
                INSERT INTO ordering_quote (id, session_id, expires_at, draft, pricing)
                VALUES (:id, :session, :expires, CAST(:draft AS jsonb), CAST(:pricing AS jsonb))
                """).param("id", quoteId).param("session", sessionId).param("expires", utc(expires))
                .param("draft", draft.toString()).param("pricing", snapshot.toString()).update();
        return snapshot.put("quoteId", quoteId.toString()).put("sessionId", sessionId.toString())
                .put("expiresAt", expires.toString());
    }

    @Transactional
    public Result submit(Actor actor, UUID key, JsonNode body) {
        UUID sessionId = id(body, "sessionId");
        ownedSession(actor, sessionId, false);
        byte[] fingerprint = digest(canonical(body).toString());
        UUID quoteId = id(body, "quoteId");
        boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:scope, 0))")
                .param("scope", sessionId + "/" + key).query(Boolean.class).single();
        if (!locked) {
            throw conflict("IDEMPOTENCY_IN_PROGRESS", "This submission is still being committed.");
        }
        var previous = jdbc.sql("""
                SELECT request_fingerprint, accepted_response FROM ordering_submission
                WHERE session_id = :session AND idempotency_key = :key
                """).param("session", sessionId).param("key", key)
                .query((row, index) -> new Submission(row.getBytes(1), parse(row.getString(2)))).optional();
        if (previous.isPresent()) {
            if (!MessageDigest.isEqual(previous.get().fingerprint(), fingerprint)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "This key has already accepted a different request.");
            }
            return new Result(200, previous.get().response());
        }
        var session = ownedSession(actor, sessionId, true);
        session.requireOpen(now());
        if (!body.path("allergyReviewed").isBoolean()) {
            throw OrderingProblem.bad("Expected a boolean allergyReviewed field.");
        }
        if (!body.path("allergyReviewed").booleanValue()) {
            throw new OrderingProblem(422, "REVIEW_REQUIRED", "Confirm allergy review before submitting.");
        }
        ObjectNode draft = OrderingPricing.draft(body);
        if (draft.path("visitAllergy").path("kind").asText().equals("UNREVIEWED")) {
            throw new OrderingProblem(422, "REVIEW_REQUIRED", "Review the visit allergy declaration.");
        }
        Quote quote = jdbc.sql("""
                SELECT expires_at, draft, pricing FROM ordering_quote
                WHERE id = :id AND session_id = :session
                AND NOT EXISTS (SELECT 1 FROM accepted_order WHERE quote_id = :id)
                """).param("id", quoteId).param("session", sessionId)
                .query((row, index) -> new Quote(instant(row, "expires_at"), parse(row.getString("draft")),
                        parse(row.getString("pricing")))).optional().orElseThrow(OrderingService::unusableQuote);
        if (!quote.expiresAt().isAfter(now())) {
            throw unusableQuote();
        }
        if (!quote.draft().equals(draft)) {
            throw new OrderingProblem(422, "INVALID_SELECTION", "The order differs from its reviewed quote.");
        }
        var runningBill = jdbc.sql("""
                SELECT count(*) AS rounds, COALESCE(sum(subtotal_minor), 0) AS subtotal
                FROM accepted_order WHERE session_id = :session
                """).param("session", sessionId)
                .query((row, index) -> new RunningBill(row.getInt("rounds"), row.getLong("subtotal"))).single();
        if (runningBill.rounds() >= 200) {
            throw new OrderingProblem(422, "INVALID_SELECTION", "The session has reached the supported number of orders.");
        }
        OrderingPricing.Price current = pricing.price(draft);
        OrderingPricing.amount(runningBill.subtotal() + current.subtotal());
        if (!canonical(quote.pricing()).toString().equals(canonical(current.snapshot()).toString())) {
            throw conflict("PRICE_CHANGED", "The catalog pricing or customization snapshot changed. Request a new quote.");
        }
        Instant acceptedAt = now();
        session.requireOpen(acceptedAt);
        if (!quote.expiresAt().isAfter(acceptedAt)) {
            throw unusableQuote();
        }
        UUID orderId = UUID.randomUUID();
        int prepMinutes = current.lines().stream().mapToInt(OrderingPricing.Line::prepMinutes).max().orElseThrow();
        jdbc.sql("""
                INSERT INTO accepted_order
                    (id, session_id, quote_id, accepted_at, status, visit_allergy, subtotal_minor, delay_at)
                VALUES (:id, :session, :quote, :accepted, 'RECEIVED', CAST(:allergy AS jsonb), :subtotal, :delay)
                """).param("id", orderId).param("session", sessionId).param("quote", quoteId)
                .param("accepted", utc(acceptedAt)).param("allergy", draft.get("visitAllergy").toString())
                .param("subtotal", current.subtotal()).param("delay", utc(acceptedAt.plus(prepMinutes, ChronoUnit.MINUTES)))
                .update();
        int position = 0;
        for (var line : current.lines()) {
            jdbc.sql("""
                    INSERT INTO accepted_order_item (id, order_id, menu_item_id, position, pricing, allergy,
                        instructions, allergy_risk, allergy_review_status, preparation_status)
                    VALUES (:id, :order, :menu, :position, CAST(:pricing AS jsonb), CAST(:allergy AS jsonb),
                        :instructions, CAST(:risk AS jsonb), :review, 'RECEIVED')
                    """).param("id", UUID.randomUUID()).param("order", orderId).param("menu", line.menuItemId())
                    .param("position", position++).param("pricing", line.pricing().toString())
                    .param("allergy", line.allergy().toString()).param("instructions", line.instructions())
                    .param("risk", line.risk().toString()).param("review", line.risk().path("unresolved").booleanValue()
                            ? "PENDING_ALLERGY_REVIEW" : "NOT_REQUIRED").update();
        }
        ObjectNode accepted = ownedOrder(actor, orderId).order();
        jdbc.sql("""
                INSERT INTO ordering_submission
                    (session_id, idempotency_key, request_fingerprint, order_id, accepted_response)
                VALUES (:session, :key, :fingerprint, :order, CAST(:response AS jsonb))
                """).param("session", sessionId).param("key", key).param("fingerprint", fingerprint)
                .param("order", orderId).param("response", accepted.toString()).update();
        outbox(orderId, 1, "ORDER_CREATED", acceptedAt);
        return new Result(201, accepted);
    }

    private record RunningBill(int rounds, long subtotal) {}

    ObjectNode order(Actor actor, UUID orderId) { return ownedOrder(actor, orderId).order(); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode history(Actor actor, UUID sessionId, String cursor, int limit) {
        ownedSession(actor, sessionId, false);
        return page(actor, sessionId, cursor, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode kitchen(Actor actor, String cursor, int limit) {
        return page(actor, null, cursor, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode bill(Actor actor, UUID sessionId) {
        ownedSession(actor, sessionId, false);
        var orders = jdbc.sql("""
                SELECT id, subtotal_minor FROM accepted_order WHERE session_id = :id ORDER BY sequence
                """).param("id", sessionId).<ObjectNode>query((row, index) -> object().put("orderId", row.getString("id"))
                        .set("totals", OrderingPricing.totals(row.getLong("subtotal_minor")))).list();
        long subtotal = 0;
        ArrayNode list = array();
        for (JsonNode order : orders) {
            subtotal = OrderingPricing.amount(subtotal + order.path("totals").path("subtotalMinor").longValue());
            list.add(order);
        }
        ObjectNode result = object().put("sessionId", sessionId.toString()).put("asOf", now().toString());
        result.set("orders", list);
        result.set("totals", OrderingPricing.totals(subtotal));
        return result;
    }

    @Transactional
    public ObjectNode transition(Actor actor, UUID itemId, String next, long expectedVersion) {
        UUID orderId = jdbc.sql("""
                SELECT o.id FROM accepted_order_item i JOIN accepted_order o ON o.id = i.order_id
                JOIN dining_session s ON s.id = o.session_id
                WHERE i.id = :item AND s.restaurant_id = :restaurant FOR UPDATE OF o
                """).param("item", itemId).param("restaurant", actor.restaurantId())
                .query(UUID.class).optional().orElseThrow(OrderingProblem::missing);
        var item = jdbc.sql("SELECT preparation_status, allergy_review_status, version FROM accepted_order_item WHERE id = :id")
                .param("id", itemId).query((row, index) -> new ItemState(row.getString(1), row.getString(2), row.getLong(3)))
                .single();
        if (item.version() != expectedVersion) {
            throw conflict("VERSION_CONFLICT", "The item version changed. Reload the ticket.");
        }
        if (!item.review().equals("NOT_REQUIRED") && !item.review().equals("APPROVED_FOR_PREPARATION")) {
            throw conflict("REVIEW_REQUIRED", "The item is held for allergy review.");
        }
        if (!(item.preparation().equals("RECEIVED") && next.equals("PREPARING")
                || item.preparation().equals("PREPARING") && next.equals("READY"))) {
            throw conflict("INVALID_TRANSITION", "Only the next preparation state is permitted.");
        }
        jdbc.sql("""
                UPDATE accepted_order_item SET preparation_status = :next, version = version + 1, updated_by = :actor
                WHERE id = :id
                """).param("next", next).param("actor", actor.credentialId()).param("id", itemId).update();
        long version = jdbc.sql("""
                UPDATE accepted_order SET version = version + 1,
                    status = CASE WHEN NOT EXISTS (SELECT 1 FROM accepted_order_item
                        WHERE order_id = :id AND preparation_status <> 'READY') THEN 'READY' ELSE 'PREPARING' END
                WHERE id = :id RETURNING version
                """).param("id", orderId).query(Long.class).single();
        Instant now = now();
        outbox(orderId, version, "ORDER_UPDATED", now);
        return readOrders("WHERE o.id = :id", Map.of("id", orderId)).getFirst().ticket(now);
    }

    private record ItemState(String preparation, String review, long version) {}

    private record OrderView(ObjectNode order, String destination, Instant delayAt, long sequence) {
        ObjectNode ticket(Instant now) {
            ObjectNode ticket = object().put("serviceDestination", destination).put("priority", "NORMAL")
                    .put("delayed", !order.path("status").asText().equals("READY") && !delayAt.isAfter(now));
            return ticket.set("order", order);
        }
    }

    private OrderView ownedOrder(Actor actor, UUID id) {
        return readOrders("WHERE o.id = :id AND s.terminal_id = :terminal AND s.restaurant_id = :restaurant",
                Map.of("id", id, "terminal", actor.terminalId(), "restaurant", actor.restaurantId()))
                .stream().findFirst().orElseThrow(OrderingProblem::missing);
    }

    private ObjectNode page(Actor actor, UUID sessionId, String rawCursor, int limit) {
        boolean kitchen = sessionId == null;
        UUID scope = kitchen ? actor.restaurantId() : sessionId;
        String cursorScope = (kitchen ? "k:" : "s:") + scope;
        String clause = kitchen ? "s.restaurant_id = :scope AND o.status IN ('RECEIVED', 'PREPARING', 'READY')"
                : "o.session_id = :scope";
        Cursor cursor = rawCursor == null
                ? new Cursor(0, jdbc.sql("""
                        SELECT COALESCE(max(o.sequence), 0) FROM accepted_order o
                        JOIN dining_session s ON s.id = o.session_id WHERE
                        """ + clause).param("scope", scope).query(Long.class).single())
                : decodeCursor(rawCursor, cursorScope);
        var rows = readOrders("WHERE " + clause + " " + """
                AND o.sequence > :after AND o.sequence <= :through ORDER BY o.sequence LIMIT :limit
                """, Map.of("scope", scope, "after", cursor.after(), "through", cursor.through(), "limit", limit + 1));
        Instant now = now();
        ArrayNode list = array();
        rows.stream().limit(limit).forEach(row -> list.add(kitchen ? row.ticket(now) : row.order()));
        ObjectNode result = object();
        result.set(kitchen ? "tickets" : "orders", list);
        return result.put("nextCursor", rows.size() > limit
                ? encodeCursor(cursorScope, rows.get(limit - 1).sequence(), cursor.through()) : null);
    }

    private static String encodeCursor(String scope, long after, long through) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (scope + ":" + after + ":" + through).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String value, String scope) {
        try {
            if (value.isEmpty() || value.length() > 500) {
                throw new IllegalArgumentException();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":");
            if (parts.length == 4 && (parts[0] + ":" + parts[1]).equals(scope)) {
                long after = Long.parseLong(parts[2]);
                long through = Long.parseLong(parts[3]);
                if (after > 0 && through >= after && value.equals(encodeCursor(scope, after, through))) {
                    return new Cursor(after, through);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid cursors always receive the same contract error.
        }
        throw OrderingProblem.bad("Invalid paging cursor.");
    }

    private List<OrderView> readOrders(String clause, Map<String, ?> parameters) {
        return jdbc.sql("""
                SELECT o.id, o.session_id, o.accepted_at, o.status, o.version, o.visit_allergy,
                    o.subtotal_minor, o.delay_at, o.sequence, s.mode, s.table_number, s.pickup_reference,
                    (SELECT jsonb_agg(jsonb_build_object(
                        'id', i.id, 'pricing', i.pricing, 'allergy', i.allergy,
                        'preparationInstructions', i.instructions, 'preparationStatus', i.preparation_status,
                        'allergyReviewStatus', i.allergy_review_status, 'allergyRisk', i.allergy_risk,
                        'version', i.version, 'billable', true, 'activeSubstitution', NULL
                    ) ORDER BY i.position) FROM accepted_order_item i WHERE i.order_id = o.id) AS lines
                FROM accepted_order o JOIN dining_session s ON s.id = o.session_id
                """ + clause).params(parameters).query((row, index) -> {
            ObjectNode order = object().put("id", row.getString("id")).put("sessionId", row.getString("session_id"))
                    .put("mode", row.getString("mode")).put("status", row.getString("status"))
                    .put("acceptedAt", instant(row, "accepted_at").toString()).put("version", row.getLong("version"));
            order.set("visitAllergy", parse(row.getString("visit_allergy")));
            order.set("lines", parse(row.getString("lines")));
            order.set("totals", OrderingPricing.totals(row.getLong("subtotal_minor")));
            String destination = row.getString("mode").equals("DINE_IN")
                    ? "Table " + row.getString("table_number") : row.getString("pickup_reference");
            return new OrderView(order, destination, instant(row, "delay_at"), row.getLong("sequence"));
        }).list();
    }

    private Device device(Actor actor, boolean lock) {
        return jdbc.sql("""
                SELECT t.id, t.table_id, d.display_number, t.version FROM ordering_terminal t
                LEFT JOIN ordering_table d ON d.id = t.table_id
                WHERE t.id = :terminal AND t.restaurant_id = :restaurant
                """ + (lock ? " FOR UPDATE OF t" : "")).param("terminal", actor.terminalId())
                .param("restaurant", actor.restaurantId()).query((row, index) -> new Device(row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class), row.getString(3), row.getLong(4))).optional()
                .orElseThrow(OrderingProblem::missing);
    }

    private Session ownedSession(Actor actor, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT * FROM dining_session WHERE id = :id AND terminal_id = :terminal AND restaurant_id = :restaurant
                """ + (lock ? " FOR UPDATE" : "")).param("id", id).param("terminal", actor.terminalId())
                .param("restaurant", actor.restaurantId()).query(OrderingService::sessionRow).optional()
                .orElseThrow(OrderingProblem::missing);
    }

    private static Session sessionRow(ResultSet row, int index) throws SQLException {
        return new Session(row.getObject("id", UUID.class), row.getString("mode"), row.getObject("table_id", UUID.class),
                row.getString("table_number"), row.getString("pickup_reference"), row.getString("status"),
                instant(row, "created_at"), instant(row, "expires_at"), instant(row, "closed_at"));
    }

    private static ObjectNode sessionJson(Session session, Instant now) {
        ObjectNode result = object().put("id", session.id().toString()).put("mode", session.mode())
                .put("status", session.currentStatus(now)).put("createdAt", session.createdAt().toString())
                .put("expiresAt", session.expiresAt().toString());
        result.put("closedAt", session.closedAt() == null ? null : session.closedAt().toString());
        if (session.mode().equals("DINE_IN")) {
            result.set("table", table(session.tableId(), session.tableNumber()));
        } else {
            result.put("pickupReference", session.pickup());
        }
        return result;
    }

    private static JsonNode table(UUID id, String display) {
        return id == null ? JSON.nullNode() : object().put("id", id.toString()).put("displayNumber", display);
    }

    private void outbox(UUID id, long version, String event, Instant now) {
        jdbc.sql("""
                INSERT INTO ordering_outbox (id, order_id, order_version, event_type, occurred_at)
                VALUES (:id, :order, :version, :event, :time)
                """).param("id", UUID.randomUUID()).param("order", id).param("version", version)
                .param("event", event).param("time", utc(now)).update();
    }

    private static OrderingProblem unusableQuote() {
        return conflict("QUOTE_EXPIRED", "The quote is unavailable. Request a new quote.");
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        var result = row.getObject(name, OffsetDateTime.class);
        return result == null ? null : result.toInstant();
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
