package com.roseroots.server.admin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("catalog")
class StaffAccounts {
    enum Role { CHEF, SUPERVISOR, MANAGER, WAITER }
    enum State { ACTIVE, DEACTIVATED, ARCHIVED }
    record Account(UUID id, String username, Role role, State state, boolean needsPasswordSetup, long version) {}
    record Create(String username, Role role, String password) {
        @Override public String toString() { return "Create[role=" + role + "]"; }
    }
    record Update(State state, long expectedVersion, String password) {
        @Override public String toString() { return "Update[state=" + state + ", expectedVersion=" + expectedVersion + "]"; }
    }
    record Created(Account account) {}

    static final class ManagedManager extends User {
        private final long securityVersion;

        ManagedManager(String username, String hash, boolean enabled, long securityVersion) {
            super(username, hash, enabled, true, true, true,
                    List.of(new SimpleGrantedAuthority("ROLE_" + AdminSecurity.MANAGER)));
            this.securityVersion = securityVersion;
        }

        long securityVersion() { return securityVersion; }
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    StaffAccounts(JdbcClient jdbc, PasswordEncoder encoder, Environment environment) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.environment = environment;
    }

    Optional<ManagedManager> manager(String username) {
        return jdbc.sql("""
                SELECT username, password_hash, enabled AND NOT password_reset_required AS usable, security_version
                FROM staff_account WHERE username = :name AND staff_role = 'MANAGER'
                """).param("name", username).query((row, index) -> new ManagedManager(row.getString(1),
                row.getString(2), row.getBoolean(3), row.getLong(4))).optional();
    }

    List<Account> list() {
        return jdbc.sql("SELECT * FROM staff_account ORDER BY username").query(StaffAccounts::account).list();
    }

    @Transactional
    public Created create(Create input, String actor) {
        String name = input.username() == null ? "" : input.username().strip().toLowerCase(Locale.ROOT);
        Role role = input.role();
        if (!name.matches("[a-z0-9][a-z0-9._-]{2,47}") || role == null
                || name.equalsIgnoreCase(bootstrapManager())
                || name.equalsIgnoreCase(environment.getProperty("rose.admin.kitchen.username", ""))) {
            throw invalid("Enter a unique staff username and role.");
        }
        lockAccounts(actor);
        String hash = encodePassword(input.password(), null);
        UUID credential = null;
        if (role != Role.MANAGER) {
            var restaurant = jdbc.sql("SELECT id FROM ordering_restaurant").query(UUID.class).optional()
                    .orElseThrow(() -> conflict("Provision the restaurant before creating staff accounts."));
            credential = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO ordering_credential (id, restaurant_id, scope, token_sha256, expires_at)
                    VALUES (:id, :restaurant, 'KITCHEN', :unused, 'infinity')
                    """).param("id", credential).param("restaurant", restaurant).param("unused", unusedDigest()).update();
        }
        UUID id = UUID.randomUUID();
        Account account;
        try {
            account = jdbc.sql("""
                    INSERT INTO staff_account (id, username, staff_role, password_hash, credential_id, kitchen_password_hash)
                    VALUES (:id, :name, :role, :managerHash, :credential, :staffHash) RETURNING *
                    """).param("id", id).param("name", name).param("role", role.name())
                    .param("managerHash", role == Role.MANAGER ? hash : null)
                    .param("staffHash", role == Role.MANAGER ? null : hash).param("credential", credential)
                    .query(StaffAccounts::account).single();
        } catch (DuplicateKeyException duplicate) {
            throw conflict("That staff username is already registered.");
        }
        audit(account, null, actor);
        return new Created(account);
    }

    @Transactional
    public Account update(UUID id, Update input, String actor) {
        if (input.expectedVersion() < 1 || input.state() == null && input.password() == null) {
            throw invalid("Supply the current version and an account change.");
        }
        lockAccounts(actor);
        var stored = jdbc.sql("SELECT * FROM staff_account WHERE id = :id FOR UPDATE").param("id", id)
                .query((row, index) -> new Stored(account(row, index), row.getObject("credential_id", UUID.class),
                        row.getString("password_hash"), row.getString("kitchen_password_hash")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var current = stored.account();
        if (current.version() != input.expectedVersion()) throw conflict("The staff account changed. Reload before editing.");
        State next = input.state() == null ? current.state() : input.state();
        boolean stateChanged = next != current.state();
        if (current.state() == State.ARCHIVED && next != State.DEACTIVATED) {
            throw conflict("Restore the archived account before setting up access.");
        }
        if (!stateChanged && input.password() == null) throw conflict("The staff account is already in that state.");
        if (next != State.ACTIVE && input.password() != null) throw invalid("Set a password when activating the account.");
        if (next == State.ACTIVE && stateChanged && input.password() == null) {
            throw conflict("Set a new staff password to activate this account.");
        }
        if (stateChanged && current.state() == State.ACTIVE && current.role() == Role.MANAGER) {
            if (current.username().equalsIgnoreCase(actor)) throw conflict("Managers cannot deactivate or archive their own account.");
            if (!bootstrapAvailable() && !jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM staff_account WHERE id <> :id AND staff_role='MANAGER'
                        AND enabled AND NOT password_reset_required)
                    """).param("id", id).query(Boolean.class).single()) {
                throw conflict("At least one usable Manager account is required.");
            }
        }
        String newHash = input.password() == null ? null : encodePassword(input.password(),
                current.role() == Role.MANAGER ? stored.managerHash() : stored.staffHash());
        if (next != State.ACTIVE || newHash != null) {
            jdbc.sql("DELETE FROM kitchen_staff_session WHERE staff_id = :id OR entry_credential_id = :credential")
                    .param("id", id).param("credential", stored.credential()).update();
            if (stored.credential() != null) {
                if (newHash != null) {
                    jdbc.sql("""
                            UPDATE ordering_credential SET token_sha256=:unused, expires_at='infinity', revoked_at=NULL
                            WHERE id=:credential
                            """).param("unused", unusedDigest()).param("credential", stored.credential()).update();
                } else {
                    jdbc.sql("UPDATE ordering_credential SET revoked_at=COALESCE(revoked_at,now()) WHERE id=:credential")
                            .param("credential", stored.credential()).update();
                }
            }
        }
        var updated = jdbc.sql("""
                UPDATE staff_account SET enabled=:active,
                    archived_at=CASE WHEN :archived THEN COALESCE(archived_at,now()) ELSE NULL END,
                    available_until=CASE WHEN :active THEN available_until ELSE NULL END,
                    password_reset_required=NOT :active,
                    password_hash=COALESCE(:managerHash,password_hash),
                    kitchen_password_hash=COALESCE(:staffHash,kitchen_password_hash),
                    security_version=security_version + CASE WHEN :invalidate THEN 1 ELSE 0 END,
                    version=version+1 WHERE id=:id RETURNING *
                """).param("active", next == State.ACTIVE).param("archived", next == State.ARCHIVED)
                .param("managerHash", current.role() == Role.MANAGER ? newHash : null)
                .param("staffHash", current.role() == Role.MANAGER ? null : newHash)
                .param("invalidate", next != State.ACTIVE || newHash != null)
                .param("id", id).query(StaffAccounts::account).single();
        audit(updated, current.state(), actor);
        return updated;
    }

    private record Stored(Account account, UUID credential, String managerHash, String staffHash) {}

    private void lockAccounts(String actor) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(182041, 21)) gate").query(Integer.class).single();
        if (!(bootstrapAvailable() && bootstrapManager().equals(actor))
                && !manager(actor).map(ManagedManager::isEnabled).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private String bootstrapManager() { return environment.getProperty("rose.admin.manager.username", ""); }

    private boolean bootstrapAvailable() {
        return !bootstrapManager().isBlank() && !environment.getProperty("rose.admin.manager.password-hash", "").isBlank();
    }

    private String encodePassword(String value, String previous) {
        if (value == null || value.length() < 12 || value.indexOf(0) >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid("Use a staff password of at least 12 characters and at most 72 UTF-8 bytes.");
        }
        if (previous != null && encoder.matches(value, previous)) throw invalid("Choose a different staff password.");
        return encoder.encode(value);
    }

    private byte[] unusedDigest() {
        byte[] unused = new byte[32];
        random.nextBytes(unused);
        return unused;
    }

    private void audit(Account account, State previous, String actor) {
        jdbc.sql("""
                INSERT INTO staff_account_audit (account_id, version, manager_username, staff_role, enabled,
                    allergy_reviewer, previous_state, new_state)
                VALUES (:id, :version, :manager, :role, :enabled, :allergy, :previous, :next)
                """).param("id", account.id()).param("version", account.version()).param("manager", actor)
                .param("role", account.role().name()).param("enabled", account.state() == State.ACTIVE)
                .param("allergy", account.role() == Role.CHEF && account.state() == State.ACTIVE)
                .param("previous", previous == null ? null : previous.name()).param("next", account.state().name()).update();
    }

    private static Account account(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        Role role = Role.valueOf(row.getString("staff_role"));
        State state = row.getObject("archived_at") != null ? State.ARCHIVED
                : row.getBoolean("enabled") ? State.ACTIVE : State.DEACTIVATED;
        return new Account(row.getObject("id", UUID.class), row.getString("username"), role, state,
                row.getBoolean("password_reset_required")
                        || role != Role.MANAGER && row.getString("kitchen_password_hash") == null, row.getLong("version"));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
