package com.roseroots.server.catalog;

import com.roseroots.server.admin.DashboardSnapshot;
import com.roseroots.server.admin.DashboardSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("catalog")
class CatalogDashboardSource implements DashboardSource {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Africa/Nairobi");
    private final CatalogService catalog;
    private final JdbcClient jdbc;

    CatalogDashboardSource(CatalogService catalog, JdbcClient jdbc) {
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardSnapshot snapshot() {
        var soldOut = catalog.snapshot(true).items().stream()
                .filter(dish -> dish.availability() == CatalogData.Availability.SOLD_OUT)
                .map(CatalogData.Dish::name).toList();
        var today = LocalDate.now(SERVICE_ZONE).atStartOfDay(SERVICE_ZONE).toOffsetDateTime();
        var totals = jdbc.sql("""
                SELECT (SELECT count(*) FROM accepted_order WHERE accepted_at >= :today) AS orders,
                    (SELECT count(*) FROM dining_session WHERE status='OPEN' AND expires_at>now()) AS visits,
                    (SELECT coalesce(sum(p.amount_minor),0) FROM payment_attempt p JOIN payment_receipt r ON r.payment_id=p.id
                       WHERE r.verified_at>=:today AND p.status='SUCCEEDED') AS sales,
                    (SELECT avg(rating) FROM visit_review) AS rating,
                    (SELECT count(*) FROM payment_attempt WHERE status='PENDING') AS payments,
                    (SELECT count(*) FROM assistance_request WHERE status<>'COMPLETED') AS assistance
                """).param("today", today).query((row, index) -> new Totals(row.getLong(1), row.getLong(2), row.getLong(3),
                        row.getBigDecimal(4), row.getLong(5), row.getLong(6))).single();
        var recent = jdbc.sql("""
                SELECT o.id, o.accepted_at, coalesce('Table '||s.table_number,s.pickup_reference),
                    (SELECT coalesce(sum((i.pricing->>'quantity')::int),0) FROM accepted_order_item i WHERE i.order_id=o.id),
                    o.payable_minor,o.status
                FROM accepted_order o JOIN dining_session s ON s.id=o.session_id ORDER BY o.accepted_at DESC,o.id LIMIT 8
                """).query((row, index) -> new DashboardSnapshot.Order(row.getObject(2, OffsetDateTime.class)
                        .atZoneSameInstant(SERVICE_ZONE).format(DateTimeFormatter.ofPattern("HH:mm")), row.getString(1),
                        row.getString(3), Long.toString(row.getLong(4)), currency(row.getLong(5)), row.getString(6))).list();
        var alerts = new ArrayList<String>();
        if (totals.payments() > 0) alerts.add(totals.payments() + " payments awaiting confirmation");
        if (totals.assistance() > 0) alerts.add(totals.assistance() + " unresolved assistance requests");
        return new DashboardSnapshot(totals.orders(), totals.visits(), currency(totals.sales()),
                totals.rating() == null ? null : totals.rating().setScale(1, RoundingMode.HALF_UP).toPlainString(), recent, soldOut, alerts);
    }

    private static String currency(long minor) { return "KSh " + BigDecimal.valueOf(minor, 2).toPlainString(); }

    private record Totals(long orders, long visits, long sales, BigDecimal rating, long payments, long assistance) {}
}
