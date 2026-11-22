package com.roseroots.server.ordering;

import com.roseroots.server.admin.OperationsSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("catalog")
class PostgresOperationsSource implements OperationsSource {
    private static final ZoneId RESTAURANT_ZONE = ZoneId.of("Africa/Nairobi");
    private static final String PROJECT = """
            SELECT o.id, o.session_id, o.accepted_at, o.status AS order_status, o.payable_minor,
                s.mode, coalesce('Table '||s.table_number,s.pickup_reference) AS service_reference,
                coalesce((SELECT sum((i.pricing->>'quantity')::int) FROM accepted_order_item i WHERE i.order_id=o.id),0) AS item_count,
                CASE WHEN o.payable_minor=0 THEN 'UNPAID' WHEN payment.status='SUCCEEDED' THEN 'SUCCESSFUL'
                     ELSE coalesce(payment.status,'UNPAID') END AS payment_status,
                payment.method AS payment_method
            FROM accepted_order o JOIN dining_session s ON s.id=o.session_id
            LEFT JOIN LATERAL (SELECT status,method FROM payment_attempt WHERE session_id=s.id
                ORDER BY (status='SUCCEEDED') DESC, created_at DESC,id DESC LIMIT 1) payment ON true
            """;
    private static final String FILTER = """
            WITH selected AS (SELECT * FROM (""" + PROJECT + """
                ) overview WHERE (CAST(:fromDate AS timestamptz) IS NULL OR accepted_at>=CAST(:fromDate AS timestamptz))
                AND (CAST(:toDate AS timestamptz) IS NULL OR accepted_at<CAST(:toDate AS timestamptz))
                AND (:orderState='' OR order_status=:orderState) AND (:paymentState='' OR payment_status=:paymentState)
                AND (:mode='' OR mode=:mode) AND (:method='' OR payment_method=:method)
                AND (:term='' OR strpos(lower(id::text),:term)>0 OR strpos(lower(service_reference),:term)>0)
            )
            """;
    private final JdbcClient jdbc;

    PostgresOperationsSource(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page orders(Query query) {
        int offset = decode(query.cursor());
        String sort = switch (query.sort()) {
            case NEWEST -> "accepted_at DESC,id";
            case OLDEST -> "accepted_at,id";
            case TOTAL_DESC -> "payable_minor DESC,accepted_at DESC,id";
            case TOTAL_ASC -> "payable_minor,accepted_at DESC,id";
            case ORDER_NUMBER -> "id";
        };
        var rows = bind(FILTER + "SELECT * FROM selected ORDER BY " + sort + " OFFSET :offset LIMIT :limit", query)
                .param("offset", offset).param("limit", query.limit() + 1).query(PostgresOperationsSource::row).list();
        var summary = bind(FILTER + """
                SELECT count(*),count(*) FILTER(WHERE order_status IN ('RECEIVED','PREPARING','READY')),
                    count(*) FILTER(WHERE order_status IN ('SERVED','COLLECTED')),
                    coalesce(sum(payable_minor) FILTER(WHERE payment_status='SUCCESSFUL'),0),
                    coalesce(sum(payable_minor) FILTER(WHERE payment_status<>'SUCCESSFUL'),0),
                    (SELECT count(*) FROM payment_attempt WHERE status='PENDING' AND session_id IN (SELECT session_id FROM selected)),
                    (SELECT count(*) FROM payment_attempt WHERE status='FAILED' AND session_id IN (SELECT session_id FROM selected))
                FROM selected
                """, query).query((result, index) -> new Summary(result.getLong(1), result.getLong(2), result.getLong(3),
                        result.getLong(4), result.getLong(5), result.getLong(6), result.getLong(7))).single();
        var methods = jdbc.sql("SELECT DISTINCT method FROM payment_attempt ORDER BY method").query(String.class).list();
        return new Page("KES", summary, methods, rows.stream().limit(query.limit()).toList(),
                rows.size() > query.limit() ? encode(offset + query.limit()) : null);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Detail> order(UUID id) {
        return jdbc.sql("SELECT * FROM (" + PROJECT + ") overview WHERE id=:id").param("id", id)
                .query((result, index) -> new VisitOrder(row(result, index), result.getObject("session_id", UUID.class)))
                .optional().map(this::detail);
    }

    private Detail detail(VisitOrder visit) {
        Row row = visit.row();
        UUID session = visit.session();
        var lines = jdbc.sql("SELECT pricing,instructions,preparation_status FROM accepted_order_item WHERE order_id=:id ORDER BY position")
                .param("id", row.id()).query((result, index) -> {
                    var price = OrderingJson.parse(result.getString("pricing"));
                    var choices = new ArrayList<SelectedOption>();
                    price.path("options").forEach(option -> choices.add(new SelectedOption(option.path("name").asText(),
                            option.path("unitAdjustmentMinor").longValue())));
                    return new Line(price.path("name").asText(), price.path("quantity").asInt(), price.path("baseUnitMinor").longValue(),
                            List.copyOf(choices), result.getString("instructions"), OrderStatus.valueOf(result.getString("preparation_status")),
                            price.path("extendedMinor").longValue());
                }).list();
        var history = new ArrayList<StatusChange>();
        history.add(new StatusChange(row.submittedAt(), OrderStatus.RECEIVED));
        jdbc.sql("SELECT completed_at,status FROM order_handoff WHERE order_id=:id").param("id", row.id())
                .query((result, index) -> new StatusChange(result.getObject(1, OffsetDateTime.class).toInstant(),
                        OrderStatus.valueOf(result.getString(2)))).optional().ifPresent(history::add);
        var attempts = jdbc.sql("""
                SELECT p.id,p.method,p.amount_minor,p.status,p.updated_at,r.id AS receipt_id,r.verified_at
                FROM payment_attempt p LEFT JOIN payment_receipt r ON r.payment_id=p.id WHERE p.session_id=:session
                ORDER BY p.created_at,p.id
                """).param("session", session).query((result, index) -> {
                    var receiptId = result.getString("receipt_id");
                    long amount = result.getLong("amount_minor");
                    String status = result.getString("status");
                    UUID id = result.getObject("id", UUID.class);
                    var receipt = receiptId == null ? null : new Receipt(receiptId,
                            result.getObject("verified_at", OffsetDateTime.class).toInstant(), amount);
                    return new Transaction(id, result.getString("method"), amount, id.toString(),
                            result.getObject("updated_at", OffsetDateTime.class).toInstant(),
                            status.equals("SUCCEEDED") ? PaymentStatus.SUCCESSFUL : PaymentStatus.valueOf(status),
                            TransactionKind.CHARGE, null, session, receipt);
                }).list();
        long sessionTotal = jdbc.sql("SELECT coalesce(sum(payable_minor),0) FROM accepted_order WHERE session_id=:session")
                .param("session", session).query(Long.class).single();
        return new Detail("KES", row, session, lines, List.copyOf(history), totals(row.totalMinor()), attempts,
                new SessionBill(session, totals(sessionTotal), sessionTotal - OperationsSource.confirmedSalesMinor(attempts)));
    }

    private JdbcClient.StatementSpec bind(String sql, Query query) {
        return jdbc.sql(sql).param("fromDate", query.from() == null ? null : query.from().atStartOfDay(RESTAURANT_ZONE).toOffsetDateTime())
                .param("toDate", query.to() == null ? null : query.to().plusDays(1).atStartOfDay(RESTAURANT_ZONE).toOffsetDateTime())
                .param("orderState", code(query.orderStatus())).param("paymentState", code(query.paymentStatus()))
                .param("mode", code(query.serviceType())).param("method", query.paymentMethod() == null ? "" : query.paymentMethod())
                .param("term", query.search().strip().toLowerCase(java.util.Locale.ROOT));
    }

    private static String code(Enum<?> value) { return value == null ? "" : value.name(); }
    private static Totals totals(long minor) { return new Totals(minor, 0L, 0L, minor); }

    private static Row row(ResultSet record, int index) throws SQLException {
        UUID id = record.getObject("id", UUID.class);
        return new Row(id, id.toString(), record.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                record.getString("service_reference"), ServiceType.valueOf(record.getString("mode")), record.getInt("item_count"),
                record.getLong("payable_minor"), OrderStatus.valueOf(record.getString("order_status")),
                PaymentStatus.valueOf(record.getString("payment_status")));
    }

    private static String encode(int value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("orders:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static int decode(String value) {
        if (value == null) return 0;
        String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        if (!raw.matches("orders:[1-9][0-9]{0,6}")) throw new IllegalArgumentException("Invalid paging cursor.");
        return Integer.parseInt(raw.substring(7));
    }

    private record VisitOrder(Row row, UUID session) {}
}
