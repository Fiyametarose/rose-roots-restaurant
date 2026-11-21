package com.roseroots.server.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read projection supplied by the shared orders, billing and payment owners. */
public interface OperationsSource {
    Page orders(Query query);
    Optional<Detail> order(UUID id);

    enum OrderStatus { RECEIVED, PREPARING, READY, SERVED, COLLECTED, CANCELLED }
    enum PaymentStatus { UNPAID, PENDING, SUCCESSFUL, FAILED, CANCELLED, REFUNDED }
    enum ServiceType { DINE_IN, TAKEAWAY }
    enum Sort { NEWEST, OLDEST, TOTAL_DESC, TOTAL_ASC, ORDER_NUMBER }
    enum TransactionKind { CHARGE, REFUND }

    record Query(String search, LocalDate from, LocalDate to, OrderStatus orderStatus,
                 PaymentStatus paymentStatus, ServiceType serviceType, String paymentMethod,
                 Sort sort, String cursor, int limit) {}

    /** A missing metric is unavailable. Zero denotes a measured zero for this query. */
    record Summary(Long totalOrders, Long activeOrders, Long completedOrders, Long confirmedSalesMinor,
                   Long outstandingMinor, Long pendingPayments, Long failedPayments) {}

    record Page(String currency, Summary summary, List<String> paymentMethods,
                List<Row> items, String nextCursor) {}

    record Row(UUID id, String orderNumber, Instant submittedAt, String serviceReference,
               ServiceType serviceType, Integer itemCount, Long totalMinor,
               OrderStatus orderStatus, PaymentStatus paymentStatus) {}

    record Detail(String currency, Row order, UUID sessionId, List<Line> items,
                  List<StatusChange> history, Totals totals, List<Transaction> payments, SessionBill sessionBill) {}

    record Line(String name, int quantity, Long baseUnitMinor, List<SelectedOption> selections,
                String instructions, OrderStatus preparationStatus, Long extendedMinor) {}

    record SelectedOption(String name, Long adjustmentMinor) {}
    record StatusChange(Instant timestamp, OrderStatus status) {}
    record Totals(Long subtotalMinor, Long serviceMinor, Long taxMinor, Long totalMinor) {}

    record Transaction(UUID id, String method, Long amountMinor, String reference, Instant timestamp,
                       PaymentStatus status, TransactionKind kind, UUID orderId, UUID sessionId, Receipt receipt) {}

    record Receipt(String number, Instant issuedAt, Long amountMinor) {}
    record SessionBill(UUID sessionId, Totals totals, Long outstandingMinor) {}

    /** Gross confirmed sales. Use only on the complete, single-currency settlement set for the query. */
    static long confirmedSalesMinor(List<Transaction> settlements) {
        var charges = new HashMap<UUID, Long>();
        for (Transaction settlement : settlements) {
            if (settlement.kind() != TransactionKind.CHARGE || settlement.status() != PaymentStatus.SUCCESSFUL) {
                continue;
            }
            if (settlement.id() == null || settlement.amountMinor() == null || settlement.amountMinor() < 0) {
                throw new IllegalArgumentException("Confirmed charges require an ID and nonnegative amount.");
            }
            var previous = charges.putIfAbsent(settlement.id(), settlement.amountMinor());
            if (previous != null && !previous.equals(settlement.amountMinor())) {
                throw new IllegalArgumentException("Inconsistent confirmed charge.");
            }
        }
        return charges.values().stream().reduce(0L, Math::addExact);
    }
}
