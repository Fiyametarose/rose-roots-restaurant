package com.roseroots.server.ordering;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.roseroots.server.ordering.OrderingController.body;
import static com.roseroots.server.ordering.OrderingController.response;
import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@RestController
@Profile("catalog")
@RequestMapping("/api/v1/kitchen/staff-session")
class KitchenStaffSessions {
    private record Account(UUID id, String username, String role, boolean allergyReviewer, String hash) {}
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String unknownPasswordHash;
    private final SecureRandom random = new SecureRandom();

    KitchenStaffSessions(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        unknownPasswordHash = encoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping
    @Transactional(noRollbackFor = OrderingProblem.class)
    public ResponseEntity<String> signIn(@AuthenticationPrincipal Actor entry, HttpServletRequest request) {
        if (!request.isSecure() || entry.personalSession()) {
            throw new OrderingProblem(403, "FORBIDDEN", "Sign in from a provisioned Kitchen launch over HTTPS.");
        }
        var input = fields(body(request), "username", "password");
        String username = text(input, "username", 48).strip().toLowerCase(Locale.ROOT);
        String password = text(input, "password", 72);
        if (password.getBytes(StandardCharsets.UTF_8).length > 72 || password.indexOf(0) >= 0) throw rejected();
        int attempts = jdbc.sql("""
                INSERT INTO kitchen_sign_in_limit (entry_credential_id, minute, attempts)
                VALUES (:entry, date_trunc('minute', now()), 1)
                ON CONFLICT (entry_credential_id) DO UPDATE SET minute = EXCLUDED.minute,
                    attempts = CASE WHEN kitchen_sign_in_limit.minute = EXCLUDED.minute
                        THEN LEAST(kitchen_sign_in_limit.attempts + 1, 21) ELSE 1 END
                RETURNING attempts
                """).param("entry", entry.credentialId()).query(Integer.class).single();
        if (attempts > 20) throw new OrderingProblem(429, "RATE_LIMITED", "Wait a minute before trying again.");
        var account = jdbc.sql("""
                SELECT s.id,s.username,s.staff_role,s.allergy_reviewer,s.kitchen_password_hash
                FROM staff_account s JOIN ordering_credential c ON c.id=s.credential_id
                WHERE s.username=:name AND s.enabled AND s.staff_role IN ('CHEF','SUPERVISOR','WAITER')
                    AND s.kitchen_password_hash IS NOT NULL AND c.scope='KITCHEN' AND c.restaurant_id=:restaurant
                    AND c.revoked_at IS NULL AND c.expires_at > now() FOR UPDATE OF s
                """).param("name", username).param("restaurant", entry.restaurantId())
                .query((row, index) -> new Account(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                        row.getBoolean(4), row.getString(5))).optional();
        if (!encoder.matches(password, account.map(Account::hash).orElse(unknownPasswordHash)) || account.isEmpty()) {
            throw rejected();
        }
        var staff = account.orElseThrow();
        byte[] material = new byte[32];
        random.nextBytes(material);
        String token = "ks_" + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        jdbc.sql("DELETE FROM kitchen_staff_session WHERE staff_id=:staff AND (expires_at <= now() OR entry_credential_id=:entry)")
                .param("staff", staff.id()).param("entry", entry.credentialId()).update();
        var expires = jdbc.sql("""
                INSERT INTO kitchen_staff_session (token_sha256, staff_id, entry_credential_id, expires_at)
                VALUES (:hash, :staff, :entry, now() + interval '30 minutes') RETURNING expires_at
                """).param("hash", digest(token)).param("staff", staff.id()).param("entry", entry.credentialId())
                .query(java.time.OffsetDateTime.class).single();
        return response(201, object().put("id", staff.id().toString()).put("username", staff.username())
                .put("role", staff.role()).put("allergyReviewer", staff.allergyReviewer())
                .put("expiresAt", expires.toInstant().toString()).put("sessionToken", token));
    }

    @DeleteMapping
    public ResponseEntity<Void> signOut(HttpServletRequest request) {
        String token = request.getHeader("Authorization").substring(7);
        jdbc.sql("DELETE FROM kitchen_staff_session WHERE token_sha256=:hash").param("hash", digest(token)).update();
        return ResponseEntity.noContent().cacheControl(org.springframework.http.CacheControl.noStore()).build();
    }

    private static OrderingProblem rejected() {
        return new OrderingProblem(401, "UNAUTHENTICATED", "Sign-in details were not accepted.");
    }

    @ExceptionHandler(OrderingProblem.class)
    ResponseEntity<String> problem(OrderingProblem problem) {
        return ResponseEntity.status(problem.status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(problem.body().toString());
    }
}
