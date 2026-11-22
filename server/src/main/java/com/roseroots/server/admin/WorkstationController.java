package com.roseroots.server.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("catalog")
@RequestMapping(AdminSecurity.PREFIX + "/kitchen-workstations")
class WorkstationController {
    record Workstation(UUID id, String label, OffsetDateTime issuedAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {}
    record Provision(String label) {}
    record Issued(Workstation workstation, String bearerToken) {
        @Override public String toString() { return "Issued[workstation=" + workstation + "]"; }
    }

    private static final String ROWS = """
            SELECT c.id, w.label, w.issued_at, c.expires_at, c.revoked_at
            FROM kitchen_workstation w JOIN ordering_credential c ON c.id = w.credential_id
            """;
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    WorkstationController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    ResponseEntity<List<Workstation>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(jdbc.sql(ROWS + " ORDER BY w.issued_at DESC, c.id").query(Workstation.class).list());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Issued> issue(@RequestBody Provision input, Principal manager, HttpServletRequest request) {
        if (!request.isSecure()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workstation issuance requires HTTPS.");
        String label = input.label() == null ? "" : input.label().strip();
        if (label.isBlank() || label.length() > 64 || label.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a workstation label of 1 to 64 characters.");
        }
        UUID restaurant = jdbc.sql("SELECT id FROM ordering_restaurant").query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Provision the restaurant first."));
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(182041, 23)) gate").query(Integer.class).single();
        if (jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM kitchen_workstation w JOIN ordering_credential c ON c.id=w.credential_id
                    WHERE c.restaurant_id=:restaurant AND lower(w.label)=lower(:label)
                        AND c.revoked_at IS NULL AND c.expires_at > now())
                """).param("restaurant", restaurant).param("label", label).query(Boolean.class).single()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active workstation already uses that label.");
        }
        byte[] material = new byte[32];
        random.nextBytes(material);
        String token = "ws_" + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ordering_credential (id, restaurant_id, scope, token_sha256, expires_at)
                VALUES (:id, :restaurant, 'KITCHEN_WORKSTATION', :hash, now() + interval '90 days')
                """).param("id", id).param("restaurant", restaurant).param("hash", digest(token)).update();
        jdbc.sql("""
                INSERT INTO kitchen_workstation (credential_id, label, issued_by) VALUES (:id, :label, :manager)
                """).param("id", id).param("label", label).param("manager", manager.getName()).update();
        var workstation = jdbc.sql(ROWS + " WHERE c.id = :id").param("id", id).query(Workstation.class).single();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new Issued(workstation, token));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> revoke(@PathVariable("id") UUID id, Principal manager) {
        int updated = jdbc.sql("""
                UPDATE kitchen_workstation SET revoked_by = COALESCE(revoked_by, :manager) WHERE credential_id = :id
                """).param("id", id).param("manager", manager.getName()).update();
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.sql("UPDATE ordering_credential SET revoked_at = COALESCE(revoked_at, now()) WHERE id = :id")
                .param("id", id).update();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
