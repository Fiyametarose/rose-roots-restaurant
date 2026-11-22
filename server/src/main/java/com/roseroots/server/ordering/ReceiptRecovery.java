package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@Service
@Profile("catalog")
class ReceiptRecovery {
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    ReceiptRecovery(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public JsonNode issue(UUID receipt, UUID visit, UUID terminal, String manager) {
        byte[] material = new byte[32];
        random.nextBytes(material);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        UUID grant = UUID.randomUUID();
        return jdbc.sql("""
                INSERT INTO receipt_recovery_grant
                    (id,receipt_id,restaurant_id,terminal_id,token_sha256,issued_by,expires_at)
                SELECT :grant,r.id,s.restaurant_id,t.id,:hash,:manager,now()+interval '5 minutes'
                FROM payment_receipt r
                JOIN payment_attempt p ON p.id=r.payment_id AND p.session_id=r.session_id
                JOIN dining_session s ON s.id=r.session_id
                JOIN ordering_terminal t ON t.id=:terminal AND t.restaurant_id=s.restaurant_id AND t.enabled
                WHERE r.id=:receipt AND s.id=:visit AND p.status='SUCCEEDED' AND p.method='CASH'
                  AND EXISTS (SELECT 1 FROM ordering_credential c
                      WHERE c.terminal_id=t.id AND c.restaurant_id=t.restaurant_id AND c.scope='TERMINAL'
                        AND c.revoked_at IS NULL AND c.expires_at>now())
                RETURNING expires_at
                """).param("grant", grant).param("receipt", receipt).param("visit", visit).param("terminal", terminal)
                .param("hash", digest(code)).param("manager", manager)
                .query((row, index) -> object().put("grantId", grant.toString()).put("code", code)
                        .put("expiresAt", row.getObject(1, OffsetDateTime.class).toInstant().toString()))
                .optional().orElseThrow(OrderingProblem::missing);
    }

    @Transactional
    public void revoke(UUID grant, String manager) {
        if (jdbc.sql("""
                UPDATE receipt_recovery_grant SET revoked_at=now(),revoked_by=:manager
                WHERE id=:grant AND revoked_at IS NULL AND completed_at IS NULL
                """).param("manager", manager).param("grant", grant).update() == 0) {
            throw OrderingProblem.missing();
        }
    }

    @Transactional
    public JsonNode redeem(Actor actor, String code, UUID key, boolean complete) {
        if (!code.matches("[A-Za-z0-9_-]{43}")) throw OrderingProblem.missing();
        // UPDATE locks the grant and rechecks eligibility after a competing mutation commits.
        UUID receipt = jdbc.sql("""
                UPDATE receipt_recovery_grant
                SET redeemed_by=coalesce(redeemed_by,:credential),
                    redeemed_at=coalesce(redeemed_at,clock_timestamp()),
                    redemption_key=coalesce(redemption_key,:key),
                    completed_at=CASE WHEN :complete THEN clock_timestamp() ELSE NULL END
                WHERE token_sha256=:hash AND restaurant_id=:restaurant AND terminal_id=:terminal
                  AND revoked_at IS NULL AND completed_at IS NULL AND expires_at>clock_timestamp()
                  AND (NOT :complete AND redeemed_at IS NULL
                       OR redeemed_by=:credential AND redemption_key=:key
                            AND redeemed_at+interval '60 seconds'>clock_timestamp())
                RETURNING receipt_id
                """).param("credential", actor.credentialId()).param("key", key).param("complete", complete)
                .param("hash", digest(code)).param("restaurant", actor.restaurantId()).param("terminal", actor.terminalId())
                .query(UUID.class).optional().orElseThrow(OrderingProblem::missing);
        if (complete) return null;
        return jdbc.sql("""
                SELECT r.receipt::text,p.amount_minor,r.payment_id,r.session_id
                FROM payment_receipt r
                JOIN payment_attempt p ON p.id=r.payment_id AND p.session_id=r.session_id
                JOIN dining_session s ON s.id=r.session_id
                WHERE r.id=:receipt AND s.restaurant_id=:restaurant AND p.status='SUCCEEDED' AND p.method='CASH'
                """).param("receipt", receipt).param("restaurant", actor.restaurantId())
                .query((row, index) -> {
                    var result = object().put("receiptId", receipt.toString()).put("amountMinor", row.getLong(2))
                            .put("paymentId", row.getString(3)).put("sessionId", row.getString(4));
                    result.set("receipt", parse(row.getString(1)));
                    return result;
                }).optional().orElseThrow(OrderingProblem::missing);
    }
}
