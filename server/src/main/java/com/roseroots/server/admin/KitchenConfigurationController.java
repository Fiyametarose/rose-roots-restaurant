package com.roseroots.server.admin;

import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("catalog")
@RequestMapping(AdminSecurity.PREFIX)
class KitchenConfigurationController {
    record Station(UUID id, String name, boolean active, int qualifiedAvailableChefs, int parallelSlots,
                   long version, List<UUID> chefIds) {}
    record StationEdit(Boolean active, Integer parallelSlots, Long expectedVersion, Set<UUID> chefIds) {}
    record Chef(UUID id, String username, boolean enabled, OffsetDateTime availableUntil, long version,
                boolean archived, boolean eligible, long unfinishedItems, OffsetDateTime serverTime) {}
    record Availability(Integer minutes, Long expectedVersion) {}
    private static final String STATIONS = """
            SELECT s.*, ARRAY(SELECT chef_id FROM kitchen_station_chef WHERE station_id = s.id ORDER BY chef_id) AS assignments,
                (SELECT count(*) FROM kitchen_station_chef a JOIN staff_account c ON c.id = a.chef_id
                 JOIN ordering_credential auth ON auth.id=c.credential_id
                 WHERE a.station_id=s.id AND s.active AND c.enabled AND c.staff_role='CHEF'
                    AND NOT c.password_reset_required AND c.available_until > now()
                    AND auth.revoked_at IS NULL AND auth.expires_at > now()) AS qualified
            FROM kitchen_station s
            """;
    private static final String CHEFS = """
            SELECT c.*, now() AS server_time,
                (c.enabled AND NOT c.password_reset_required AND auth.revoked_at IS NULL
                    AND auth.expires_at > now()) AS eligible,
                (SELECT count(*) FROM accepted_order_item i WHERE i.claimed_by=c.id
                    AND i.preparation_status IN ('RECEIVED','PREPARING')) AS unfinished_items
            FROM staff_account c JOIN ordering_credential auth ON auth.id=c.credential_id
            WHERE c.staff_role='CHEF'
            """;

    private final JdbcClient jdbc;

    KitchenConfigurationController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/stations")
    ResponseEntity<List<Station>> stations() {
        return ok(jdbc.sql(STATIONS + " ORDER BY s.name").query(KitchenConfigurationController::station).list());
    }

    @PatchMapping("/stations/{id}")
    @Transactional
    public ResponseEntity<Station> updateStation(@PathVariable("id") UUID id, @RequestBody StationEdit input, Principal manager) {
        if (input.active() == null || input.parallelSlots() == null || input.parallelSlots() < 0 || input.parallelSlots() > 1000
                || input.expectedVersion() == null || input.expectedVersion() < 1 || input.chefIds() == null
                || input.chefIds().size() > 200 || input.chefIds().stream().anyMatch(java.util.Objects::isNull)) {
            throw bad("Supply station state, slot capacity, current version and assigned Chef IDs.");
        }
        if (!input.chefIds().isEmpty() && jdbc.sql("SELECT count(*) FROM staff_account WHERE staff_role = 'CHEF' AND id IN (:ids)")
                .param("ids", input.chefIds()).query(Long.class).single() != input.chefIds().size()) {
            throw bad("Every assignment must refer to a registered Chef.");
        }
        var version = jdbc.sql("""
                UPDATE kitchen_station SET active = :active, parallel_slots = :slots, version = version + 1
                WHERE id = :id AND version = :expected RETURNING version
                """).param("active", input.active()).param("slots", input.parallelSlots()).param("id", id)
                .param("expected", input.expectedVersion()).query(Long.class).optional().orElseThrow(KitchenConfigurationController::stale);
        jdbc.sql("DELETE FROM kitchen_station_chef WHERE station_id = :id").param("id", id).update();
        for (UUID chef : input.chefIds()) {
            jdbc.sql("INSERT INTO kitchen_station_chef(station_id, chef_id) VALUES (:station,:chef)")
                    .param("station", id).param("chef", chef).update();
        }
        audit("STATION", id, version, manager);
        return ok(jdbc.sql(STATIONS + " WHERE s.id = :id").param("id", id).query(KitchenConfigurationController::station).single());
    }

    @GetMapping("/chef-availability")
    ResponseEntity<List<Chef>> chefs() {
        return ok(jdbc.sql(CHEFS + " ORDER BY c.username")
                .query(KitchenConfigurationController::chef).list());
    }

    @PatchMapping("/chef-availability/{id}")
    @Transactional
    public ResponseEntity<Chef> updateAvailability(@PathVariable("id") UUID id, @RequestBody Availability input, Principal manager) {
        if (input.minutes() == null || input.minutes() < 0 || input.minutes() > 720
                || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw bad("Specify 0 to 720 minutes of availability and the current account version.");
        }
        jdbc.sql("""
                UPDATE staff_account SET available_until = CASE WHEN :minutes = 0 THEN NULL
                    ELSE now() + make_interval(mins => :minutes) END, version = version + 1
                WHERE id = :id AND staff_role = 'CHEF' AND enabled AND NOT password_reset_required AND version = :expected
                    AND (:minutes = 0 OR EXISTS(SELECT 1 FROM ordering_credential auth
                        WHERE auth.id=staff_account.credential_id AND auth.revoked_at IS NULL AND auth.expires_at > now()))
                RETURNING version
                """).param("id", id).param("minutes", input.minutes()).param("expected", input.expectedVersion())
                .query(Long.class).optional().orElseThrow(KitchenConfigurationController::stale);
        var chef = jdbc.sql(CHEFS + " AND c.id=:id").param("id", id).query(KitchenConfigurationController::chef).single();
        audit("CHEF_AVAILABILITY", id, chef.version(), manager);
        return ok(chef);
    }

    private void audit(String entity, UUID id, long version, Principal actor) {
        jdbc.sql("""
                INSERT INTO kitchen_configuration_audit(entity, entity_id, version, manager_username)
                VALUES (:entity,:id,:version,:manager)
                """).param("entity", entity).param("id", id).param("version", version).param("manager", actor.getName()).update();
    }

    private static Station station(ResultSet row, int index) throws SQLException {
        var assignments = row.getArray("assignments");
        try {
            return new Station(row.getObject("id", UUID.class), row.getString("name"), row.getBoolean("active"),
                    row.getInt("qualified"), row.getInt("parallel_slots"), row.getLong("version"),
                    Arrays.stream((Object[]) assignments.getArray()).map(value -> UUID.fromString(value.toString())).toList());
        } finally {
            assignments.free();
        }
    }

    private static Chef chef(ResultSet row, int index) throws SQLException {
        return new Chef(row.getObject("id", UUID.class), row.getString("username"), row.getBoolean("enabled"),
                row.getObject("available_until", OffsetDateTime.class), row.getLong("version"),
                row.getObject("archived_at") != null, row.getBoolean("eligible"),
                row.getLong("unfinished_items"), row.getObject("server_time", OffsetDateTime.class));
    }

    private static <T> ResponseEntity<T> ok(T data) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The current record is unavailable or has changed. Reload it before saving.");
    }
}
