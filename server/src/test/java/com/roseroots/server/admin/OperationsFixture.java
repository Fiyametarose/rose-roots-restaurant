package com.roseroots.server.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static com.roseroots.server.admin.OperationsSource.*;

@TestConfiguration(proxyBeanMethods = false)
public class OperationsFixture {
    static final UUID READY_ID = UUID.fromString("11111111-1234-4234-8123-111111111111");
    static final UUID SERVED_ID = UUID.fromString("22222222-1234-4234-8123-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-1234-4234-8123-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-20T19:12:00Z");

    @Bean
    OperationsSource sampleOperations() {
        var ready = new Row(READY_ID, "RR-1042", NOW, "Table 07", ServiceType.DINE_IN,
                1, 185000L, OrderStatus.READY, PaymentStatus.UNPAID);
        var served = new Row(SERVED_ID, "RR-1041", NOW.minusSeconds(260), "Table 03", ServiceType.DINE_IN,
                1, 220000L, OrderStatus.SERVED, PaymentStatus.SUCCESSFUL);
        var pickup = new Row(UUID.fromString("44444444-1234-4234-8123-444444444444"), "RR-1040",
                NOW.minusSeconds(710), "Pickup P-018", ServiceType.TAKEAWAY,
                2, 125000L, OrderStatus.PREPARING, PaymentStatus.PENDING);
        var readyTotals = new Totals(165000L, 10000L, 10000L, 185000L);
        var paidTotals = new Totals(210000L, 10000L, 0L, 220000L);
        var pending = transaction("52609dff-12f0-40ae-bcc9-72e85d53b113", ready.id(), "CASH",
                185000, PaymentStatus.PENDING, null, null);
        var failed = transaction("52609dff-12f0-40ae-bcc9-72e85d53b114", served.id(), "M_PESA",
                220000, PaymentStatus.FAILED, null, null);
        var paid = transaction("52609dff-12f0-40ae-bcc9-72e85d53b115", served.id(), "CARD",
                220000, PaymentStatus.SUCCESSFUL, "FIXTURE-TXN-1041", new Receipt("FIXTURE-081", NOW, 220000L));
        var inProgress = transaction("52609dff-12f0-40ae-bcc9-72e85d53b116", pickup.id(), "M_PESA",
                125000, PaymentStatus.PENDING, null, null);
        var detail = new Detail("KES", ready, SESSION,
                List.of(new Line("Doro Wat", 1, 135000L,
                        List.of(new SelectedOption("Large portion", 30000L)), "Sauce on the side.",
                        OrderStatus.READY, 165000L)),
                List.of(new StatusChange(NOW, OrderStatus.RECEIVED),
                        new StatusChange(NOW.plusSeconds(150), OrderStatus.READY)),
                readyTotals, List.of(pending), new SessionBill(SESSION, readyTotals, 185000L));
        var paidDetail = new Detail("KES", served, SESSION, List.of(new Line("Grilled Fish", 1, 210000L,
                List.of(), null, OrderStatus.SERVED, 210000L)), null, paidTotals, List.of(failed, paid),
                new SessionBill(SESSION, paidTotals, 0L));
        var pickupDetail = new Detail("KES", pickup, SESSION, List.of(new Line("Spiced Chai", 2,
                62500L, List.of(), null, OrderStatus.PREPARING, 125000L)), null,
                new Totals(125000L, 0L, 0L, 125000L), List.of(inProgress), null);
        var records = List.of(detail, paidDetail, pickupDetail);

        return new OperationsSource() {
            @Override public Page orders(Query query) {
                var matches = records.stream().filter(row -> matches(row, query)).toList();
                var transactions = matches.stream().flatMap(row -> row.payments().stream()).toList();
                long completed = matches.stream().filter(row -> row.order().orderStatus() == OrderStatus.SERVED).count();
                long outstanding = matches.stream().filter(row -> row.order().paymentStatus() != PaymentStatus.SUCCESSFUL)
                        .mapToLong(row -> row.order().totalMinor()).sum();
                var summary = new Summary((long) matches.size(), matches.size() - completed, completed,
                        confirmedSalesMinor(transactions), outstanding, count(transactions, PaymentStatus.PENDING),
                        count(transactions, PaymentStatus.FAILED));
                var sorted = matches.stream().map(Detail::order).sorted(sorting(query.sort())).toList();
                int offset = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
                int end = Math.min(offset + query.limit(), sorted.size());
                return new Page("KES", summary, List.of("CASH", "M_PESA", "CARD"),
                        sorted.subList(offset, end), end < sorted.size() ? String.valueOf(end) : null);
            }

            @Override public Optional<Detail> order(UUID id) {
                return records.stream().filter(record -> record.order().id().equals(id)).findFirst();
            }
        };
    }

    private static boolean matches(Detail detail, Query query) {
        Row row = detail.order();
        var date = row.submittedAt().atZone(ZoneOffset.UTC).toLocalDate();
        String searchable = (row.orderNumber() + " " + row.serviceReference() + " " + detail.items().getFirst().name())
                .toLowerCase(java.util.Locale.ROOT);
        return searchable.contains(query.search())
                && (query.from() == null || !date.isBefore(query.from()))
                && (query.to() == null || !date.isAfter(query.to()))
                && (query.orderStatus() == null || row.orderStatus() == query.orderStatus())
                && (query.paymentStatus() == null || row.paymentStatus() == query.paymentStatus())
                && (query.serviceType() == null || row.serviceType() == query.serviceType())
                && (query.paymentMethod() == null || detail.payments().stream().anyMatch(payment -> query.paymentMethod().equals(payment.method())));
    }

    private static Comparator<Row> sorting(Sort sort) {
        return switch (sort) {
            case NEWEST -> Comparator.comparing(Row::submittedAt).reversed();
            case OLDEST -> Comparator.comparing(Row::submittedAt);
            case TOTAL_DESC -> Comparator.comparing(Row::totalMinor).reversed();
            case TOTAL_ASC -> Comparator.comparing(Row::totalMinor);
            case ORDER_NUMBER -> Comparator.comparing(Row::orderNumber);
        };
    }

    private static long count(List<Transaction> transactions, PaymentStatus status) {
        return transactions.stream().filter(tx -> tx.status() == status).count();
    }

    private static Transaction transaction(String id, UUID order, String method, long amount, PaymentStatus state,
                                           String reference, Receipt receipt) {
        return new Transaction(UUID.fromString(id), method, amount, reference, NOW,
                state, TransactionKind.CHARGE, order, SESSION, receipt);
    }
}
