package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingProblem.conflict;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@Service
@Profile("catalog")
class AssistanceRequests {
    private static final List<String> STATES = List.of("SUBMITTED", "ACKNOWLEDGED", "IN_PROGRESS", "COMPLETED");
    private static final String ROWS = """
            SELECT a.*, s.mode, s.table_number, s.pickup_reference
            FROM assistance_request a JOIN dining_session s ON s.id = a.session_id
            """;
    private final JdbcClient jdbc;
    private final OrderingService ordering;

    AssistanceRequests(JdbcClient jdbc, OrderingService ordering) {
        this.jdbc = jdbc;
        this.ordering = ordering;
    }

    @Transactional
    public OrderingService.Result submit(Actor actor, UUID session, UUID key, String type, String note) {
        ordering.ownedSession(actor, session, false);
        if (!type.equals("OTHER") && !note.isEmpty()) {
            throw new OrderingProblem(422, "INVALID_SELECTION", "A note is allowed for Other Assistance only.");
        }
        byte[] fingerprint = digest(object().put("type", type).put("note", note).toString());
        boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:scope, 0))")
                .param("scope", "assistance/" + session + "/" + key).query(Boolean.class).single();
        if (!locked) {
            throw conflict("IDEMPOTENCY_IN_PROGRESS", "The assistance request is still being committed.");
        }
        var prior = jdbc.sql(ROWS + "WHERE a.session_id = :session AND a.submission_key = :key")
                .param("session", session).param("key", key).query(AssistanceRequests::row).optional();
        if (prior.isPresent()) {
            var request = prior.get();
            if (!MessageDigest.isEqual(request.fingerprint(), fingerprint)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "This key already accepted a different assistance request.");
            }
            return new OrderingService.Result(200, request.replay());
        }
        var owned = ordering.ownedSession(actor, session, true);
        // Serialize insert order within the restaurant so cursor high-water marks are stable.
        jdbc.sql("SELECT id FROM ordering_restaurant WHERE id = :id FOR UPDATE")
                .param("id", actor.restaurantId()).query(UUID.class).single();
        OffsetDateTime now = now();
        owned.requireOpen(now.toInstant());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO assistance_request (id, session_id, submission_key, input_sha256, type, note,
                    status, submitted_at, updated_at)
                VALUES (:id, :session, :key, :hash, :type, :note, 'SUBMITTED', :now, :now)
                """).param("id", id).param("session", session).param("key", key).param("hash", fingerprint)
                .param("type", type).param("note", note).param("now", now).update();
        return new OrderingService.Result(201, request(id, actor.restaurantId(), false).json());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode page(Actor actor, UUID session, String cursor, int limit) {
        boolean kitchen = session == null;
        if (!kitchen) ordering.ownedSession(actor, session, false);
        UUID scope = kitchen ? actor.restaurantId() : session;
        String cursorScope = (kitchen ? "ak:" : "as:") + scope;
        String condition = kitchen ? "s.restaurant_id = :scope" : "a.session_id = :scope";
        OrderingService.Cursor bounds = cursor == null
                ? new OrderingService.Cursor(0, jdbc.sql("""
                        SELECT COALESCE(max(a.sequence), 0) FROM assistance_request a
                        JOIN dining_session s ON s.id = a.session_id WHERE
                        """ + condition).param("scope", scope).query(Long.class).single())
                : OrderingService.decodeCursor(cursor, cursorScope);
        var rows = jdbc.sql(ROWS + "WHERE " + condition + " " + """
                 AND a.sequence > :after AND a.sequence <= :through ORDER BY a.sequence LIMIT :limit
                """).param("scope", scope).param("after", bounds.after()).param("through", bounds.through())
                .param("limit", limit + 1).query(AssistanceRequests::row).list();
        var entries = array();
        rows.stream().limit(limit).forEach(row -> entries.add(row.json()));
        return object().put("nextCursor", rows.size() > limit
                        ? OrderingService.encodeCursor(cursorScope, rows.get(limit - 1).sequence(), bounds.through()) : null)
                .set("requests", entries);
    }

    @Transactional
    public ObjectNode transition(Actor actor, UUID id, String next, long expectedVersion) {
        Request row = request(id, actor.restaurantId(), true);
        if (row.version() != expectedVersion) {
            throw conflict("VERSION_CONFLICT", "The assistance request changed. Reload the queue.");
        }
        int step = STATES.indexOf(row.status()) + 1;
        if (step >= STATES.size() || !STATES.get(step).equals(next)) {
            throw conflict("INVALID_TRANSITION", "Advance assistance one state at a time.");
        }
        OffsetDateTime stamp = now();
        if (stamp.isBefore(row.updatedAt())) stamp = row.updatedAt();
        jdbc.sql("""
                UPDATE assistance_request SET status = :status, version = version + 1, updated_at = :now WHERE id = :id
                """).param("status", next).param("now", stamp).param("id", id).update();
        jdbc.sql("""
                INSERT INTO assistance_transition (request_id, staff_credential_id, from_status, to_status, version, occurred_at)
                VALUES (:id, :actor, :previous, :status, :version, :now)
                """).param("id", id).param("actor", actor.credentialId()).param("previous", row.status())
                .param("status", next).param("version", row.version() + 1).param("now", stamp).update();
        return request(id, actor.restaurantId(), false).json();
    }

    private Request request(UUID id, UUID restaurant, boolean lock) {
        return jdbc.sql(ROWS + "WHERE a.id = :id AND s.restaurant_id = :restaurant" + (lock ? " FOR UPDATE OF a" : ""))
                .param("id", id).param("restaurant", restaurant).query(AssistanceRequests::row).optional()
                .orElseThrow(OrderingProblem::missing);
    }

    private static Request row(ResultSet row, int index) throws SQLException {
        String mode = row.getString("mode");
        return new Request(row.getString("id"), row.getString("session_id"), mode,
                mode.equals("DINE_IN") ? "Table " + row.getString("table_number") : row.getString("pickup_reference"),
                row.getString("type"), row.getString("note"), row.getString("status"),
                row.getObject("submitted_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class),
                row.getLong("version"), row.getLong("sequence"), row.getBytes("input_sha256"));
    }

    private record Request(String id, String session, String mode, String destination, String type, String note,
                           String status, OffsetDateTime submittedAt, OffsetDateTime updatedAt, long version,
                           long sequence, byte[] fingerprint) {
        ObjectNode json() {
            return object().put("id", id).put("sessionId", session).put("mode", mode)
                    .put("serviceDestination", destination).put("type", type).put("note", note).put("status", status)
                    .put("submittedAt", submittedAt.toInstant().toString()).put("updatedAt", updatedAt.toInstant().toString())
                    .put("version", version);
        }

        ObjectNode replay() { return json().put("status", "SUBMITTED").put("version", 1)
                .put("updatedAt", submittedAt.toInstant().toString()); }
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
}
