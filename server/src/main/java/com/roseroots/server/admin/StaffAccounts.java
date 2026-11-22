package com.roseroots.server.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("catalog")
class StaffAccounts {
    enum Role { CHEF, SUPERVISOR, MANAGER }
    record Account(UUID id, String username, Role role, boolean enabled, boolean allergyReviewer, long version) {}
    record Create(String username, Role role, Boolean allergyReviewer, String password) {}
    record Update(Boolean enabled, Boolean allergyReviewer, long expectedVersion, String kitchenPassword) {}
    record Created(Account account, String bearerToken) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    StaffAccounts(JdbcClient jdbc, PasswordEncoder encoder, Environment environment) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.environment = environment;
    }

    Optional<UserDetails> manager(String username) {
        return jdbc.sql("""
                SELECT username, password_hash, enabled FROM staff_account
                WHERE username = :name AND staff_role = 'MANAGER'
                """).param("name", username).<UserDetails>query((row, index) -> User.withUsername(row.getString(1))
                .password(row.getString(2)).roles(AdminSecurity.MANAGER).disabled(!row.getBoolean(3)).build()).optional();
    }

    List<Account> list() {
        return jdbc.sql("""
                SELECT id, username, staff_role, enabled, allergy_reviewer, version
                FROM staff_account ORDER BY username
                """).query(StaffAccounts::account).list();
    }

    @Transactional
    public Created create(Create input, String actor) {
        String name = input.username() == null ? "" : input.username().strip().toLowerCase(Locale.ROOT);
        Role role = input.role();
        boolean allergy = Boolean.TRUE.equals(input.allergyReviewer());
        if (!name.matches("[a-z0-9][a-z0-9._-]{2,47}") || role == null || allergy && role != Role.CHEF
                || name.equalsIgnoreCase(environment.getProperty("rose.admin.manager.username", ""))
                || name.equalsIgnoreCase(environment.getProperty("rose.admin.kitchen.username", ""))) {
            throw invalid("Enter a unique staff username and a valid role and permission combination.");
        }
        String hash = null;
        UUID credential = null;
        String token = null;
        if (role == Role.MANAGER) {
            String password = input.password();
            if (password == null || password.length() < 12 || password.indexOf(0) >= 0
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw invalid("Use a Manager password of at least 12 characters and at most 72 UTF-8 bytes.");
            }
            hash = encoder.encode(password);
        } else {
            if (input.password() != null && !input.password().isEmpty()) throw invalid("Kitchen roles use issued credentials.");
            var restaurant = jdbc.sql("SELECT id FROM ordering_restaurant").query(UUID.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Provision the restaurant before issuing a Kitchen credential."));
            credential = UUID.randomUUID();
            byte[] material = new byte[32];
            random.nextBytes(material);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
            jdbc.sql("""
                    INSERT INTO ordering_credential (id, restaurant_id, scope, token_sha256, expires_at)
                    VALUES (:id, :restaurant, 'KITCHEN', :hash, now() + interval '90 days')
                    """).param("id", credential).param("restaurant", restaurant).param("hash", digest(token)).update();
        }
        var account = new Account(UUID.randomUUID(), name, role, true, allergy, 1);
        try {
            jdbc.sql("""
                    INSERT INTO staff_account (id, username, staff_role, password_hash, credential_id, allergy_reviewer)
                    VALUES (:id, :name, :role, :password, :credential, :allergy)
                    """).param("id", account.id()).param("name", name).param("role", role.name())
                    .param("password", hash).param("credential", credential).param("allergy", allergy).update();
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That staff username is already registered.");
        }
        audit(account, actor);
        return new Created(account, token);
    }

    @Transactional
    public Account update(UUID id, Update input, String actor) {
        if (input.enabled() == null || input.allergyReviewer() == null || input.expectedVersion() < 1) {
            throw invalid("Supply the enabled state, Chef permission, and current version.");
        }
        var current = jdbc.sql("SELECT * FROM staff_account WHERE id = :id FOR UPDATE").param("id", id)
                .query(StaffAccounts::account).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (current.version() != input.expectedVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The staff account changed. Reload before editing.");
        }
        if (input.allergyReviewer() && current.role() != Role.CHEF || !input.enabled() && current.username().equals(actor)) {
            throw invalid("Only Chefs may review allergies and Managers cannot disable their own account.");
        }
        if (input.kitchenPassword() != null) {
            String password = input.kitchenPassword();
            if (current.role() == Role.MANAGER || password.length() < 12 || password.indexOf(0) >= 0
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw invalid("Use a Kitchen password of at least 12 characters and at most 72 UTF-8 bytes.");
            }
            byte[] unused = new byte[32];
            random.nextBytes(unused);
            jdbc.sql("""
                    UPDATE ordering_credential SET token_sha256 = :unused, expires_at = 'infinity'
                    WHERE id = (SELECT credential_id FROM staff_account WHERE id = :id)
                    """).param("unused", unused).param("id", id).update();
            jdbc.sql("UPDATE staff_account SET kitchen_password_hash = :hash WHERE id = :id")
                    .param("hash", encoder.encode(password)).param("id", id).update();
        }
        if (!input.enabled() || input.kitchenPassword() != null) {
            jdbc.sql("DELETE FROM kitchen_staff_session WHERE staff_id = :id").param("id", id).update();
        }
        var updated = jdbc.sql("""
                UPDATE staff_account SET enabled = :enabled, allergy_reviewer = :allergy, version = version + 1
                WHERE id = :id RETURNING *
                """).param("enabled", input.enabled()).param("allergy", input.allergyReviewer()).param("id", id)
                .query(StaffAccounts::account).single();
        audit(updated, actor);
        return updated;
    }

    private void audit(Account account, String actor) {
        jdbc.sql("""
                INSERT INTO staff_account_audit (account_id, version, manager_username, staff_role, enabled, allergy_reviewer)
                VALUES (:id, :version, :manager, :role, :enabled, :allergy)
                """).param("id", account.id()).param("version", account.version()).param("manager", actor)
                .param("role", account.role().name()).param("enabled", account.enabled())
                .param("allergy", account.allergyReviewer()).update();
    }

    private static Account account(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Account(row.getObject("id", UUID.class), row.getString("username"), Role.valueOf(row.getString("staff_role")),
                row.getBoolean("enabled"), row.getBoolean("allergy_reviewer"), row.getLong("version"));
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
