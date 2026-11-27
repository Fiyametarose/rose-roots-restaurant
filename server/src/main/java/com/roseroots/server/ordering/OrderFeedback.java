package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.roseroots.server.admin.ReviewSource.Topic;
import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@Service
@Profile("catalog")
class OrderFeedback {
    private static final EnumSet<Topic> ORDER_TOPICS = EnumSet.of(Topic.FOOD_QUALITY, Topic.TASTE,
            Topic.TEMPERATURE, Topic.ORDER_ACCURACY, Topic.PREPARATION_SPEED);
    private static final String ELIGIBLE = """
            EXISTS (SELECT 1 FROM order_handoff h WHERE h.order_id=o.id)
            AND EXISTS (SELECT 1 FROM accepted_order_item i WHERE i.order_id=o.id AND i.preparation_status IN ('SERVED','COLLECTED'))
            AND NOT EXISTS (SELECT 1 FROM accepted_order_item i WHERE i.order_id=o.id
                AND i.preparation_status NOT IN ('SERVED','COLLECTED','CANCELLED'))
            """;

    private final JdbcClient jdbc;
    private final OrderingService ordering;
    private final ZoneId zone;

    OrderFeedback(JdbcClient jdbc, OrderingService ordering, @Value("${rose.reviews.zone:Africa/Nairobi}") String zone) {
        this.jdbc = jdbc;
        this.ordering = ordering;
        this.zone = ZoneId.of(zone);
    }

    @Transactional
    public OrderingService.Result submit(Actor actor, UUID session, UUID order, UUID item, UUID key, JsonNode body) {
        FeedbackInput input = input(body, item != null);
        ordering.ownedSession(actor, session, true);
        boolean served = jdbc.sql("SELECT (" + ELIGIBLE + ") FROM accepted_order o WHERE id=:order AND session_id=:session")
                .param("order", order).param("session", session).query(Boolean.class).optional()
                .orElseThrow(OrderingProblem::missing);
        Item line = item == null ? null : jdbc.sql("""
                SELECT menu_item_id, pricing->>'name', preparation_status IN ('SERVED','COLLECTED')
                    AND EXISTS(SELECT 1 FROM order_handoff h WHERE h.order_id=i.order_id)
                FROM accepted_order_item i WHERE i.order_id=:order AND i.id=:item
                """).param("order", order).param("item", item)
                .query((row, n) -> new Item(row.getObject(1, UUID.class), row.getString(2), row.getBoolean(3)))
                .optional().orElseThrow(OrderingProblem::missing);
        var previous = jdbc.sql("""
                SELECT request_key, rating, topics, comment, id, submitted_at FROM accepted_order_feedback
                WHERE order_id=:order AND item_id IS NOT DISTINCT FROM CAST(:item AS uuid)
                """).param("order", order).param("item", item).query((row, n) -> new Stored(
                        row.getObject("request_key", UUID.class), row.getInt("rating"), parse(row.getString("topics")),
                        row.getString("comment"), row.getObject("id", UUID.class),
                        row.getObject("submitted_at", OffsetDateTime.class))).optional();
        if (previous.isPresent()) {
            var saved = previous.get();
            if (!saved.key().equals(key)) throw OrderingProblem.conflict("REVIEW_ALREADY_EXISTS", "Feedback already exists for this item or order.");
            if (saved.rating() != input.rating() || !saved.topics().equals(input.topics()) || !saved.comment().equals(input.comment())) {
                throw OrderingProblem.conflict("IDEMPOTENCY_CONFLICT", "This key already accepted different feedback.");
            }
            return new OrderingService.Result(200, receipt(saved.id(), order, item, saved.rating(), saved.time()));
        }
        if (line == null ? !served : !line.served()) {
            throw new OrderingProblem(422, "REVIEW_NOT_ELIGIBLE", "Feedback is available after service or pickup.");
        }
        UUID id = UUID.randomUUID();
        var time = jdbc.sql("""
                INSERT INTO accepted_order_feedback (id, order_id, item_id, request_key, rating, topics, comment, menu_item_id, dish_name)
                VALUES (:id,:order,:item,:key,:rating,CAST(:topics AS jsonb),:comment,:menu,:name) RETURNING submitted_at
                """).param("id", id).param("order", order).param("item", item).param("key", key)
                .param("rating", input.rating()).param("topics", input.topics().toString()).param("comment", input.comment())
                .param("menu", line == null ? null : line.menuId()).param("name", line == null ? null : line.name())
                .query(OffsetDateTime.class).single();
        return new OrderingService.Result(201, receipt(id, order, item, input.rating(), time));
    }

    private record Item(UUID menuId, String name, boolean served) {}
    private record FeedbackInput(int rating, JsonNode topics, String comment) {}
    private record Stored(UUID key, int rating, JsonNode topics, String comment, UUID id, OffsetDateTime time) {}

    private static FeedbackInput input(JsonNode body, boolean dish) {
        if (body == null || !body.isObject()) throw OrderingProblem.bad("Use a feedback object.");
        body.fieldNames().forEachRemaining(name -> {
            if (!name.equals("rating") && !name.equals("comment") && !(name.equals("topics") && !dish)) {
                throw OrderingProblem.bad("Unexpected feedback field.");
            }
        });
        var topics = EnumSet.noneOf(Topic.class);
        if (body.has("topics")) {
            for (var value : list(body, "topics", 0, ORDER_TOPICS.size())) {
                var topic = ORDER_TOPICS.stream().filter(t -> t.name().equals(value.textValue())).findFirst();
                if (topic.isEmpty() || !topics.add(topic.get())) throw OrderingProblem.bad("Use distinct order feedback topics.");
            }
        }
        return new FeedbackInput((int) integer(body, "rating", 1, 5), JSON.valueToTree(topics),
                body.has("comment") ? VisitReviews.plain(text(body, "comment", 500)) : "");
    }

    private static ObjectNode receipt(UUID id, UUID order, UUID item, int rating, OffsetDateTime time) {
        return object().put("id", id.toString()).put("orderId", order.toString())
                .put("itemId", item == null ? null : item.toString()).put("rating", rating)
                .put("submittedAt", time.toInstant().toString());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode report(UUID restaurant, String type, String dish, Integer rating, LocalDate from, LocalDate to,
                             int offset, int limit) {
        if (!java.util.Set.of("ALL", "DISH", "ORDER").contains(type) || dish.length() > 120
                || rating != null && (rating < 1 || rating > 5)
                || from != null && to != null && from.isAfter(to)
                || offset < 0 || limit < 1 || limit > 100) {
            throw OrderingProblem.bad("Check the feedback filters.");
        }
        String scope = """
                FROM accepted_order_feedback f JOIN accepted_order o ON o.id=f.order_id
                JOIN dining_session s ON s.id=o.session_id
                WHERE (CAST(:restaurant AS uuid) IS NULL OR s.restaurant_id=:restaurant)
                  AND (:type='ALL' OR (:type='DISH')=(f.item_id IS NOT NULL))
                  AND (:dish='' OR strpos(lower(f.dish_name),lower(:dish))>0)
                  AND (CAST(:rating AS integer) IS NULL OR f.rating=:rating)
                  AND (CAST(:from AS timestamptz) IS NULL OR f.submitted_at>=:from)
                  AND (CAST(:through AS timestamptz) IS NULL OR f.submitted_at<:through)
                """;
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("restaurant", restaurant);
        parameters.put("type", type);
        parameters.put("dish", dish.strip());
        parameters.put("rating", rating);
        parameters.put("from", from == null ? null : from.atStartOfDay(zone).toOffsetDateTime());
        parameters.put("through", to == null ? null : to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        var rows = jdbc.sql("""
                SELECT f.rating, f.topics, f.comment, f.dish_name, f.submitted_at, o.id AS order_ref
                """ + scope + " ORDER BY f.submitted_at DESC, f.id DESC OFFSET :offset LIMIT :limit")
                .params(parameters).param("offset", offset).param("limit", limit + 1).query((r,n) -> {
                    String name = r.getString("dish_name");
                    var review = object().put("type", name == null ? "ORDER" : "DISH").put("dish", name)
                            .put("orderReference", r.getString("order_ref").substring(0, 8).toUpperCase(java.util.Locale.ROOT))
                            .put("rating", r.getInt("rating")).put("comment", r.getString("comment"))
                            .put("submittedAt", r.getObject("submitted_at", OffsetDateTime.class).toInstant().toString());
                    review.set("topics", parse(r.getString("topics")));
                    return review;
                }).list();
        var reviews = array();
        rows.stream().limit(limit).forEach(reviews::add);
        var dishes = array();
        if (restaurant == null) {
            jdbc.sql("""
                    SELECT (array_agg(f.dish_name ORDER BY f.submitted_at DESC, f.id DESC))[1] AS dish,
                           count(*) AS reviews, avg(f.rating) AS average
                    """ + scope + " AND f.item_id IS NOT NULL GROUP BY f.menu_item_id ORDER BY reviews DESC, dish, f.menu_item_id")
                    .params(parameters).query((row, n) -> object().put("dish", row.getString("dish"))
                            .put("count", row.getLong("reviews")).put("average", row.getBigDecimal("average")))
                    .list().forEach(dishes::add);
        }
        var response = object().put("hasMore", rows.size() > limit);
        response.set("reviews", reviews);
        response.set("dishes", dishes);
        return response;
    }
}
