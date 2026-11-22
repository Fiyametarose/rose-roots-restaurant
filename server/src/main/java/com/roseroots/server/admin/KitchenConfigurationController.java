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
    record Chef(UUID id, String username, boolean enabled, OffsetDateTime availableUntil, long version) {}
    record Availability(Integer minutes, Long expectedVersion) {}
    record ProfileInput(UUID stationId, Integer durationSeconds, Long expectedVersion) {}
    record PrepTime(UUID menuItemId, UUID stationId, int durationSeconds, long version) {}
    record OptionWork(UUID groupId, UUID optionId, int lowerSeconds, int upperSeconds) {}
    record EstimateWorkload(int upperDurationSeconds, long expectedVersion, List<OptionWork> options) {}

    private static final String STATIONS = """
            SELECT s.*, ARRAY(SELECT chef_id FROM kitchen_station_chef WHERE station_id = s.id ORDER BY chef_id) AS assignments,
                (SELECT count(*) FROM kitchen_station_chef a JOIN staff_account c ON c.id = a.chef_id
                 WHERE a.station_id = s.id AND s.active AND c.enabled AND c.available_until > now()) AS qualified
            FROM kitchen_station s
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
        return ok(jdbc.sql("SELECT * FROM staff_account WHERE staff_role = 'CHEF' ORDER BY username")
                .query(KitchenConfigurationController::chef).list());
    }

    @PatchMapping("/chef-availability/{id}")
    @Transactional
    public ResponseEntity<Chef> updateAvailability(@PathVariable("id") UUID id, @RequestBody Availability input, Principal manager) {
        if (input.minutes() == null || input.minutes() < 0 || input.minutes() > 720
                || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw bad("Specify 0 to 720 minutes of availability and the current account version.");
        }
        var chef = jdbc.sql("""
                UPDATE staff_account SET available_until = CASE WHEN :minutes = 0 THEN NULL
                    ELSE now() + make_interval(mins => :minutes) END, version = version + 1
                WHERE id = :id AND staff_role = 'CHEF' AND enabled AND version = :expected RETURNING *
                """).param("id", id).param("minutes", input.minutes()).param("expected", input.expectedVersion())
                .query(KitchenConfigurationController::chef).optional().orElseThrow(KitchenConfigurationController::stale);
        audit("CHEF_AVAILABILITY", id, chef.version(), manager);
        return ok(chef);
    }

    @GetMapping("/prep-times")
    ResponseEntity<List<PrepTime>> prepTimes() {
        return ok(jdbc.sql("SELECT * FROM kitchen_prep_profile ORDER BY menu_item_id")
                .query(KitchenConfigurationController::profile).list());
    }

    @PatchMapping("/prep-times/{id}")
    @Transactional
    public ResponseEntity<PrepTime> updateProfile(@PathVariable("id") UUID menu, @RequestBody ProfileInput input, Principal manager) {
        if (input.stationId() == null || input.durationSeconds() == null || input.durationSeconds() < 1
                || input.durationSeconds() > 86400 || input.expectedVersion() == null || input.expectedVersion() < 0) {
            throw bad("Specify a station, preparation duration in seconds, and the current version.");
        }
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM catalog_item WHERE id = :menu AND NOT archived)
                   AND EXISTS(SELECT 1 FROM kitchen_station WHERE id = :station)
                """).param("menu", menu).param("station", input.stationId()).query(Boolean.class).single();
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog item or station unavailable.");
        var statement = input.expectedVersion() == 0 ? jdbc.sql("""
                INSERT INTO kitchen_prep_profile(menu_item_id, station_id, duration_seconds)
                VALUES (:menu,:station,:seconds) ON CONFLICT DO NOTHING RETURNING *
                """) : jdbc.sql("""
                UPDATE kitchen_prep_profile SET station_id = :station, duration_seconds = :seconds, version = version + 1,
                    upper_duration_seconds = CASE WHEN station_id = :station AND duration_seconds = :seconds
                        THEN upper_duration_seconds ELSE NULL END
                WHERE menu_item_id = :menu AND version = :expected RETURNING *
                """).param("expected", input.expectedVersion());
        var profile = statement.param("menu", menu).param("station", input.stationId()).param("seconds", input.durationSeconds())
                .query(KitchenConfigurationController::profile).optional().orElseThrow(KitchenConfigurationController::stale);
        audit("PREP_PROFILE", menu, profile.version(), manager);
        return ok(profile);
    }

    @PatchMapping("/prep-times/{id}/estimate-workload")
    @Transactional
    public ResponseEntity<PrepTime> calibrate(@PathVariable("id") UUID menu, @RequestBody EstimateWorkload input, Principal manager) {
        if (input.expectedVersion() < 1 || input.upperDurationSeconds() < 1 || input.upperDurationSeconds() > 86400
                || input.options() == null || input.options().size() > 900) {
            throw bad("Supply a measured upper duration, profile version and option workload list.");
        }
        var keys = new java.util.HashSet<java.util.Map.Entry<UUID, UUID>>();
        for (var option : input.options()) {
            if (option == null || option.groupId() == null || option.optionId() == null || option.lowerSeconds() < 0
                    || option.upperSeconds() < option.lowerSeconds() || option.upperSeconds() > 86400
                    || !keys.add(java.util.Map.entry(option.groupId(), option.optionId()))) {
                throw bad("Option workload IDs must be unique and their measured durations valid.");
            }
        }
        var profile = jdbc.sql("""
                UPDATE kitchen_prep_profile SET upper_duration_seconds = :upper, version = version + 1
                WHERE menu_item_id = :menu AND version = :expected AND duration_seconds <= :upper RETURNING *
                """).param("upper", input.upperDurationSeconds()).param("menu", menu).param("expected", input.expectedVersion())
                .query(KitchenConfigurationController::profile).optional().orElseThrow(KitchenConfigurationController::stale);
        jdbc.sql("DELETE FROM kitchen_option_workload WHERE menu_item_id = :menu").param("menu", menu).update();
        for (var option : input.options()) {
            jdbc.sql("""
                    INSERT INTO kitchen_option_workload(menu_item_id, group_id, option_id, lower_seconds, upper_seconds)
                    VALUES (:menu, :group, :option, :low, :high)
                    """).param("menu", menu).param("group", option.groupId()).param("option", option.optionId())
                    .param("low", option.lowerSeconds()).param("high", option.upperSeconds()).update();
        }
        audit("PREP_PROFILE", menu, profile.version(), manager);
        return ok(profile);
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
                row.getObject("available_until", OffsetDateTime.class), row.getLong("version"));
    }

    private static PrepTime profile(ResultSet row, int index) throws SQLException {
        return new PrepTime(row.getObject("menu_item_id", UUID.class), row.getObject("station_id", UUID.class),
                row.getInt("duration_seconds"), row.getLong("version"));
    }

    private static <T> ResponseEntity<T> ok(T data) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The current record is unavailable or has changed. Reload it before saving.");
    }
}
