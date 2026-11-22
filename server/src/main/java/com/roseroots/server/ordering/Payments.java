package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingProblem.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@Service
@Profile("catalog")
class Payments {
    private final JdbcClient jdbc;
    private final OrderingService ordering;

    Payments(JdbcClient jdbc, OrderingService ordering) {
        this.jdbc = jdbc;
        this.ordering = ordering;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode balance(Actor actor, UUID sessionId) {
        var visit = ordering.ownedSession(actor, sessionId, false);
        var facts = facts(sessionId);
        var blocked = array();
        if (facts.held()) blocked.add("CHEF_DECISION_PENDING");
        if (facts.pending() != null) blocked.add("PAYMENT_PENDING");
        if (!visit.currentStatus(Instant.now()).equals("OPEN")) blocked.add("SESSION_NOT_OPEN");
        if (facts.paid() > 0) blocked.add("ALREADY_SETTLED");
        else if (facts.payable() == 0) blocked.add("NOTHING_DUE");
        var result = object().put("sessionId", sessionId.toString()).put("billVersion", facts.version()).put("currency", "KES")
                .put("payableMinor", facts.payable()).put("settledMinor", facts.paid())
                .put("outstandingMinor", facts.payable() - facts.paid())
                .put("openAttemptId", facts.pending() == null ? null : facts.pending().toString());
        result.set("orders", orders(sessionId));
        result.set("blockedReasons", blocked);
        result.putArray("availableMethods").add("CASH");
        return result;
    }

    @Transactional
    public OrderingService.Result start(Actor actor, UUID session, UUID key, String method, long expected) {
        var visit = ordering.ownedSession(actor, session, true);
        var replay = jdbc.sql(ATTEMPTS + " WHERE p.session_id=:session AND p.idempotency_key=:key")
                .param("session", session).param("key", key).query(Payments::attemptRow).optional();
        if (replay.isPresent()) {
            var saved = replay.get();
            if (!saved.method().equals(method) || saved.billVersion() != expected) {
                throw conflict("IDEMPOTENCY_CONFLICT", "The key was already used for a different payment request.");
            }
            return new OrderingService.Result(200, saved.json());
        }
        if (!method.equals("CASH")) throw new OrderingProblem(422, "PAYMENT_METHOD_UNAVAILABLE", "The payment method is not enabled.");
        visit.requireOpen(Instant.now());
        var facts = facts(session);
        checkBill(facts, expected);
        if (facts.held()) throw conflict("CHEF_DECISION_PENDING", "A Chef decision is still outstanding.");
        if (facts.pending() != null) throw conflict("PAYMENT_PENDING", "A payment is already pending for this visit.");
        if (facts.paid() > 0) throw conflict("ALREADY_SETTLED", "This visit already has a verified settlement.");
        if (facts.payable() == 0) throw conflict("NOTHING_DUE", "There is no payable balance.");
        UUID id = UUID.randomUUID();
        var snapshot = object();
        snapshot.set("orders", orders(session));
        snapshot.set("lines", lines(session));
        jdbc.sql("""
                INSERT INTO payment_attempt(id,session_id,idempotency_key,method,status,bill_version,amount_minor,currency,snapshot)
                VALUES (:id,:session,:key,:method,'PENDING',:bill,:amount,'KES',CAST(:snapshot AS jsonb))
                """).param("id", id).param("session", session).param("key", key).param("method", method)
                .param("bill", expected).param("amount", facts.payable()).param("snapshot", snapshot.toString()).update();
        audit(id, 1, "STARTED", actor.credentialId(), null, null);
        return new OrderingService.Result(201, attempt(session, id).json());
    }

    ObjectNode read(Actor actor, UUID session, UUID id) {
        ordering.ownedSession(actor, session, false);
        return attempt(session, id).json();
    }

    @Transactional
    public ObjectNode cancel(Actor actor, UUID session, UUID id, long expected) {
        ordering.ownedSession(actor, session, true);
        var payment = attempt(session, id);
        if (payment.cancellationRequested() && payment.status().equals("PENDING") && payment.version() == expected + 1) {
            return payment.json();
        }
        pending(payment, expected);
        if (payment.cancellationRequested()) return payment.json();
        jdbc.sql("UPDATE payment_attempt SET cancellation_requested=true, version=version+1, updated_at=now() WHERE id=:id")
                .param("id", id).update();
        audit(id, expected + 1, "CANCELLATION_REQUESTED", actor.credentialId(), null, null);
        return attempt(session, id).json();
    }

    JsonNode receipt(Actor actor, UUID session, UUID id) {
        ordering.ownedSession(actor, session, false);
        return jdbc.sql("SELECT receipt::text FROM payment_receipt WHERE id=:id AND session_id=:session")
                .param("id", id).param("session", session).query(String.class).optional().map(OrderingJson::parse)
                .orElseThrow(OrderingProblem::missing);
    }

    JsonNode staffQueue() {
        var result = array();
        jdbc.sql(ATTEMPTS + " WHERE p.status='PENDING' AND p.method='CASH' ORDER BY p.created_at,p.id LIMIT 200")
                .query(Payments::attemptRow).list().forEach(payment -> result.add(payment.json()));
        return result;
    }

    @Transactional
    public ObjectNode confirmCash(UUID id, long expected, long reviewedBill, String manager) {
        UUID session = lockPaymentSession(id);
        var payment = attempt(session, id);
        if (payment.status().equals("SUCCEEDED") && payment.version() == expected + 1 && payment.billVersion() == reviewedBill) {
            return payment.json();
        }
        pending(payment, expected);
        if (!payment.method().equals("CASH")) throw conflict("INVALID_TRANSITION", "Only collected cash can be confirmed here.");
        var facts = facts(session);
        checkBill(facts, reviewedBill);
        if (payment.billVersion() != reviewedBill || facts.payable() != payment.amount()) {
            throw conflict("BILL_VERSION_CONFLICT", "The verified payment amount no longer matches the balance.");
        }
        if (facts.held()) throw conflict("CHEF_DECISION_PENDING", "A Chef decision is still outstanding.");
        var now = Instant.now();
        UUID receiptId = UUID.randomUUID();
        var receipt = object().put("id", receiptId.toString()).put("sessionId", session.toString())
                .put("paymentId", id.toString()).put("method", payment.method()).put("currency", "KES")
                .put("amountMinor", payment.amount()).put("verifiedAt", now.toString());
        receipt.set("orders", payment.snapshot().path("orders"));
        receipt.set("lines", payment.snapshot().path("lines"));
        finishPayment(id, "SUCCEEDED");
        jdbc.sql("""
                INSERT INTO payment_receipt(id,session_id,payment_id,verified_at,receipt)
                VALUES (:id,:session,:payment,:now,CAST(:receipt AS jsonb))
                """).param("id", receiptId).param("session", session).param("payment", id)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("receipt", receipt.toString()).update();
        advanceBill(jdbc, session);
        audit(id, expected + 1, "CASH_CONFIRMED", null, manager, null);
        return attempt(session, id).json();
    }

    @Transactional
    public ObjectNode resolveCash(UUID id, long expected, String status, String reason, String manager) {
        UUID session = lockPaymentSession(id);
        var payment = attempt(session, id);
        pending(payment, expected);
        if (!payment.method().equals("CASH") || status.equals("CANCELLED") && !payment.cancellationRequested()) {
            throw conflict("INVALID_TRANSITION", "Cancellation requires a recorded request for an uncollected cash payment.");
        }
        finishPayment(id, status);
        audit(id, expected + 1, "CASH_" + status, null, manager, reason);
        return attempt(session, id).json();
    }

    @Transactional
    public ObjectNode close(UUID session, long expected, String manager) {
        String status = jdbc.sql("SELECT status FROM dining_session WHERE id=:id FOR UPDATE")
                .param("id", session).query(String.class).optional().orElseThrow(OrderingProblem::missing);
        var facts = facts(session);
        checkBill(facts, expected);
        if (facts.held()) throw conflict("CHEF_DECISION_PENDING", "A Chef decision is still outstanding.");
        if (facts.pending() != null) throw conflict("PAYMENT_PENDING", "The visit still has a pending payment.");
        if (facts.payable() != facts.paid()) throw conflict("OUTSTANDING_BALANCE", "Settle the outstanding balance before closing.");
        if (facts.awaitingHandoff()) throw conflict("HANDOFF_REQUIRED", "Payable food must be served or collected before closing.");
        if (!status.equals("CLOSED")) {
            jdbc.sql("UPDATE dining_session SET status='CLOSED',closed_at=now() WHERE id=:id").param("id", session).update();
            jdbc.sql("""
                    INSERT INTO dining_session_closure(session_id,manager_username,bill_version,closed_at)
                    SELECT id,:manager,bill_version,closed_at FROM dining_session WHERE id=:id
                    """).param("id", session).param("manager", manager).update();
        }
        var closed = jdbc.sql("SELECT closed_at FROM dining_session WHERE id=:id").param("id", session).query(OffsetDateTime.class).single();
        return object().put("sessionId", session.toString()).put("status", "CLOSED").put("billVersion", expected)
                .put("closedAt", closed.toInstant().toString());
    }

    static void requireMutable(JdbcClient jdbc, UUID session) {
        var state = jdbc.sql("SELECT status FROM payment_attempt WHERE session_id=:id AND status IN ('PENDING','SUCCEEDED')")
                .param("id", session).query(String.class).optional();
        state.ifPresent(value -> {
            throw value.equals("PENDING") ? conflict("PAYMENT_PENDING", "The payable snapshot is locked by an unresolved payment.")
                    : conflict("ALREADY_SETTLED", "The settled visit cannot accept further payable changes.");
        });
    }

    static void advanceBill(JdbcClient jdbc, UUID session) {
        jdbc.sql("UPDATE dining_session SET bill_version=bill_version+1 WHERE id=:id").param("id", session).update();
    }

    private UUID lockPaymentSession(UUID id) {
        return jdbc.sql("""
                SELECT s.id FROM payment_attempt p JOIN dining_session s ON s.id=p.session_id WHERE p.id=:id FOR UPDATE OF s
                """).param("id", id).query(UUID.class).optional().orElseThrow(OrderingProblem::missing);
    }

    private Facts facts(UUID session) {
        return jdbc.sql("""
                SELECT s.bill_version,
                    coalesce((SELECT sum(payable_minor) FROM accepted_order WHERE session_id=s.id),0) AS payable,
                    coalesce((SELECT sum(amount_minor) FROM payment_attempt WHERE session_id=s.id AND status='SUCCEEDED'),0) AS paid,
                    (SELECT id FROM payment_attempt WHERE session_id=s.id AND status='PENDING') AS pending,
                    EXISTS(SELECT 1 FROM accepted_order o JOIN accepted_order_item i ON i.order_id=o.id
                        WHERE o.session_id=s.id AND i.preparation_status <> 'CANCELLED'
                        AND i.allergy_review_status NOT IN ('NOT_REQUIRED','APPROVED_FOR_PREPARATION','DECLINED')) AS held,
                    EXISTS(SELECT 1 FROM accepted_order o JOIN accepted_order_item i ON i.order_id=o.id
                        WHERE o.session_id=s.id AND i.preparation_status NOT IN ('CANCELLED','SERVED','COLLECTED')) AS awaiting_handoff
                FROM dining_session s WHERE s.id=:session
                """).param("session", session).query((row, index) -> new Facts(row.getLong(1), row.getLong(2), row.getLong(3),
                        row.getObject(4, UUID.class), row.getBoolean(5), row.getBoolean(6))).single();
    }

    private JsonNode orders(UUID session) {
        return parse(jdbc.sql("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('orderId',id,'amountMinor',payable_minor) ORDER BY sequence),'[]'::jsonb)::text
                FROM accepted_order WHERE session_id=:session AND payable_minor > 0
                """).param("session", session).query(String.class).single());
    }

    private JsonNode lines(UUID session) {
        return parse(jdbc.sql("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('orderId',o.id,'itemId',i.id,'pricing',i.pricing)
                    ORDER BY o.sequence,i.position),'[]'::jsonb)::text
                FROM accepted_order o JOIN accepted_order_item i ON i.order_id=o.id
                WHERE o.session_id=:session AND i.preparation_status <> 'CANCELLED'
                """).param("session", session).query(String.class).single());
    }

    private void finishPayment(UUID id, String status) {
        jdbc.sql("UPDATE payment_attempt SET status=:status, version=version+1, updated_at=now() WHERE id=:id")
                .param("id", id).param("status", status).update();
    }

    private void audit(UUID id, long version, String event, UUID terminal, String manager, String reason) {
        jdbc.sql("""
                INSERT INTO payment_audit(payment_id,version,event,terminal_credential_id,manager_username,reason)
                VALUES (:id,:version,:event,:terminal,:manager,:reason)
                """).param("id", id).param("version", version).param("event", event).param("terminal", terminal)
                .param("manager", manager).param("reason", reason).update();
    }

    private static void pending(Attempt payment, long expected) {
        if (payment.version() != expected) throw conflict("VERSION_CONFLICT", "Reload the current payment attempt first.");
        if (!payment.status().equals("PENDING")) throw conflict("INVALID_TRANSITION", "The payment has reached a final state.");
    }

    private static void checkBill(Facts facts, long expected) {
        if (facts.version() != expected) throw conflict("BILL_VERSION_CONFLICT", "Review the current payable balance first.");
    }

    private Attempt attempt(UUID session, UUID id) {
        return jdbc.sql(ATTEMPTS + " WHERE p.session_id=:session AND p.id=:id").param("session", session).param("id", id)
                .query(Payments::attemptRow).optional().orElseThrow(OrderingProblem::missing);
    }

    private static final String ATTEMPTS = "SELECT p.*,r.id AS receipt_id FROM payment_attempt p LEFT JOIN payment_receipt r ON r.payment_id=p.id";

    private static Attempt attemptRow(ResultSet row, int index) throws SQLException {
        return new Attempt(row.getString("id"), row.getString("session_id"), row.getString("method"), row.getString("status"),
                row.getLong("bill_version"), row.getLong("version"), row.getLong("amount_minor"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                row.getBoolean("cancellation_requested"), row.getString("receipt_id"), parse(row.getString("snapshot")));
    }

    private record Facts(long version, long payable, long paid, UUID pending, boolean held, boolean awaitingHandoff) {}

    private record Attempt(String id, String sessionId, String method, String status, long billVersion, long version,
                           long amount, Instant created, Instant updated, boolean cancellationRequested, String receipt, JsonNode snapshot) {
        ObjectNode json() {
            return object().put("id", id).put("sessionId", sessionId).put("method", method).put("status", status).put("currency", "KES")
                    .put("billVersion", billVersion).put("version", version).put("amountMinor", amount).put("createdAt", created.toString())
                    .put("updatedAt", updated.toString()).put("cancellationRequested", cancellationRequested).put("receiptId", receipt);
        }
    }
}
