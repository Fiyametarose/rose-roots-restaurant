package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roseroots.server.admin.ReviewInsights;
import com.roseroots.server.admin.ReviewSource;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
class VisitReviews implements ReviewSource {
    private static final EnumSet<Topic> FOOD = EnumSet.of(Topic.FOOD_QUALITY, Topic.TASTE, Topic.TEMPERATURE,
            Topic.ORDER_ACCURACY, Topic.PREPARATION_SPEED);
    private static final String SELECT = """
            SELECT r.*, s.mode, s.table_number, s.pickup_reference
            FROM visit_review r JOIN dining_session s ON s.id = r.session_id
            """;
    private final JdbcClient jdbc;
    private final OrderingService ordering;
    private final ZoneId businessZone;

    VisitReviews(JdbcClient jdbc, OrderingService ordering, @Value("${rose.reviews.zone:Africa/Nairobi}") String zone) {
        this.jdbc = jdbc;
        this.ordering = ordering;
        this.businessZone = ZoneId.of(zone);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode eligibility(Actor actor, UUID session) {
        ordering.ownedSession(actor, session, false);
        var prior = bySession(session);
        return object().put("eligible", handedOff(session)).set("review",
                prior.map(Row::customer).map(JsonNode.class::cast).orElseGet(JSON::nullNode));
    }

    record Submission(Integer rating, EnumSet<Topic> topics, String comment) {
        byte[] fingerprint() {
            return digest(object().put("rating", rating).put("comment", comment)
                    .set("topics", JSON.valueToTree(topics)).toString());
        }
    }

    @Transactional
    public OrderingService.Result submit(Actor actor, UUID session, UUID key, Submission input) {
        ordering.ownedSession(actor, session, true);
        var prior = bySession(session);
        byte[] fingerprint = input.fingerprint();
        if (prior.isPresent()) {
            Row review = prior.get();
            if (!review.key().equals(key)) {
                throw conflict("REVIEW_ALREADY_EXISTS", "A review has already been submitted for this session.");
            }
            if (!MessageDigest.isEqual(review.fingerprint(), fingerprint)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "This key already accepted a different review.");
            }
            return new OrderingService.Result(200, review.customer());
        }
        if (!handedOff(session)) {
            throw new OrderingProblem(422, "REVIEW_NOT_ELIGIBLE", "A review is available after food is served or collected.");
        }
        jdbc.sql("SELECT id FROM ordering_restaurant WHERE id = :id FOR UPDATE")
                .param("id", actor.restaurantId()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO visit_review (id, session_id, key, fingerprint, rating, topics, comment, submitted_at)
                VALUES (:id, :session, :key, :fingerprint, :rating, CAST(:topics AS jsonb), :comment, :now)
                """).param("id", UUID.randomUUID()).param("session", session).param("key", key)
                .param("fingerprint", fingerprint).param("rating", input.rating())
                .param("topics", JSON.valueToTree(input.topics()).toString()).param("comment", input.comment())
                .param("now", now()).update();
        return new OrderingService.Result(201, bySession(session).orElseThrow().customer());
    }

    private boolean handedOff(UUID session) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM accepted_order o JOIN order_handoff h ON h.order_id = o.id
                    JOIN accepted_order_item i ON i.order_id = o.id
                    WHERE o.session_id = :session AND i.preparation_status IN ('SERVED', 'COLLECTED')
                )
                """).param("session", session).query(Boolean.class).single();
    }

    private java.util.Optional<Row> bySession(UUID session) {
        return jdbc.sql(SELECT + "WHERE r.session_id = :session").param("session", session).query(VisitReviews::row).optional();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode kitchen(Actor actor, String cursor, int limit) {
        String scope = "kr:" + actor.restaurantId();
        var bounds = cursor == null ? new OrderingService.Cursor(0, jdbc.sql("""
                SELECT COALESCE(max(r.sequence), 0) FROM visit_review r JOIN dining_session s ON s.id = r.session_id
                WHERE s.restaurant_id = :restaurant
                """).param("restaurant", actor.restaurantId()).query(Long.class).single())
                : OrderingService.decodeCursor(cursor, scope);
        var rows = jdbc.sql(SELECT + """
                WHERE s.restaurant_id = :restaurant AND r.sequence <= :through AND r.sequence < :before
                AND jsonb_exists_any(r.topics, ARRAY['FOOD_QUALITY','TASTE','TEMPERATURE','ORDER_ACCURACY','PREPARATION_SPEED'])
                ORDER BY r.sequence DESC LIMIT :limit
                """).param("restaurant", actor.restaurantId()).param("through", bounds.through())
                .param("before", cursor == null ? Long.MAX_VALUE : bounds.after())
                .param("limit", limit + 1).query(VisitReviews::row).list();
        var results = array();
        rows.stream().limit(limit).forEach(row -> results.add(row.kitchen()));
        return object().put("nextCursor", rows.size() > limit
                        ? OrderingService.encodeCursor(scope, rows.get(limit - 1).sequence(), bounds.through()) : null)
                .set("reviews", results);
    }

    @Override
    @Transactional(readOnly = true)
    public Page reviews(Query filters) {
        var search = filters.search().toLowerCase(Locale.ROOT);
        List<Review> matches = jdbc.sql(SELECT + "ORDER BY r.sequence DESC").query(VisitReviews::row).list()
                .stream().map(Row::review).filter(row -> {
                    var date = row.submittedAt().atZone(businessZone).toLocalDate();
                    return (filters.from() == null || !date.isBefore(filters.from()))
                            && (filters.to() == null || !date.isAfter(filters.to()))
                            && (filters.rating() == null || filters.rating().equals(row.rating()))
                            && (filters.topic() == null || row.topics().contains(filters.topic()))
                            && (filters.followUp() == null || filters.followUp() == row.followUp())
                            && (search.isEmpty() || row.comment().toLowerCase(Locale.ROOT).contains(search)
                                    || row.id().toString().contains(search) || row.sessionId().toString().contains(search)
                                    || row.serviceReference().toLowerCase(Locale.ROOT).contains(search));
                }).toList();
        int start = 0;
        if (filters.cursor() != null && !filters.cursor().isBlank()) {
            while (start < matches.size() && !matches.get(start).id().toString().equals(filters.cursor())) start++;
            if (start == matches.size()) throw new IllegalArgumentException("The review cursor is no longer valid.");
            start++;
        }
        int end = Math.min(start + filters.limit(), matches.size());
        return new Page(ReviewInsights.aggregate(matches, businessZone, true), matches.subList(start, end),
                end < matches.size() ? matches.get(end - 1).id().toString() : null);
    }

    @Transactional
    public ObjectNode followUp(UUID id, String actor, FollowUp followUp, String response, long expectedVersion) {
        var existing = jdbc.sql(SELECT + "WHERE r.id = :id FOR UPDATE OF r").param("id", id)
                .query(VisitReviews::row).optional().orElseThrow(OrderingProblem::missing).review();
        if (existing.version() != expectedVersion) {
            throw conflict("VERSION_CONFLICT", "The Manager follow-up changed. Reload the review.");
        }
        long version = expectedVersion + 1;
        jdbc.sql("""
                UPDATE visit_review SET follow_up = :follow, internal_response = :response, version = :version
                WHERE id = :id
                """).param("follow", followUp.name()).param("response", response).param("version", version)
                .param("id", id).update();
        jdbc.sql("""
                INSERT INTO review_follow_up_audit
                    (review_id, version, manager, from_state, to_state, internal_response, changed_at)
                VALUES (:id, :version, :manager, :previous, :follow, :response, :now)
                """).param("id", id).param("version", version).param("manager", actor)
                .param("previous", existing.followUp().name()).param("follow", followUp.name())
                .param("response", response).param("now", now()).update();
        return object().put("id", id.toString()).put("followUp", followUp.name())
                .put("internalResponse", response).put("version", version);
    }

    static String plain(String value) {
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw OrderingProblem.bad("Use plain review text.");
        }
        return value.strip();
    }

    private static Row row(ResultSet row, int index) throws SQLException {
        var topics = EnumSet.noneOf(Topic.class);
        parse(row.getString("topics")).forEach(value -> topics.add(Topic.valueOf(value.asText())));
        String destination = row.getString("mode").equals("DINE_IN")
                ? "Table " + row.getString("table_number") : row.getString("pickup_reference");
        var review = new Review(row.getObject("id", UUID.class), row.getObject("session_id", UUID.class), destination,
                row.getObject("submitted_at", OffsetDateTime.class).toInstant(), row.getObject("rating", Integer.class),
                List.copyOf(topics), row.getString("comment"), FollowUp.valueOf(row.getString("follow_up")),
                row.getString("internal_response"), row.getLong("version"));
        return new Row(review, row.getObject("key", UUID.class), row.getBytes("fingerprint"), row.getLong("sequence"));
    }

    private record Row(Review review, UUID key, byte[] fingerprint, long sequence) {
        ObjectNode customer() { return projection(review.topics()).put("sessionId", review.sessionId().toString()); }
        ObjectNode kitchen() { return projection(review.topics().stream().filter(FOOD::contains).toList()); }
        ObjectNode projection(List<Topic> topics) {
            return object().put("id", review.id().toString()).put("submittedAt", review.submittedAt().toString())
                    .put("rating", review.rating()).put("comment", review.comment()).set("topics", JSON.valueToTree(topics));
        }
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
}
