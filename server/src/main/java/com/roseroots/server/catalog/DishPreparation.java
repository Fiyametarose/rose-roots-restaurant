package com.roseroots.server.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static com.roseroots.server.catalog.CatalogData.*;

@Component
@Profile("catalog")
class DishPreparation {
    private final JdbcClient jdbc;

    DishPreparation(JdbcClient jdbc) { this.jdbc = jdbc; }

    Map<UUID, Preparation> read(List<UUID> dishes, boolean includeOptions) {
        if (dishes.isEmpty()) return Map.of();
        Map<UUID, List<OptionWork>> options = new HashMap<>();
        if (includeOptions) {
            jdbc.sql("""
                    SELECT * FROM kitchen_option_workload WHERE menu_item_id IN (:ids) ORDER BY group_id,option_id
                    """).param("ids", dishes).query().listOfRows().forEach(row -> {
                var item = (UUID) row.get("menu_item_id");
                options.computeIfAbsent(item, ignored -> new ArrayList<>()).add(new OptionWork(
                        (UUID) row.get("group_id"), (UUID) row.get("option_id"),
                        (Integer) row.get("lower_seconds"), (Integer) row.get("upper_seconds")));
            });
        }
        var profiles = new HashMap<UUID, Preparation>();
        jdbc.sql("""
                SELECT p.*, s.name AS station_name FROM kitchen_prep_profile p JOIN kitchen_station s ON s.id=p.station_id
                WHERE p.menu_item_id IN (:ids)
                """).param("ids", dishes).query((row, index) -> {
            UUID id = row.getObject("menu_item_id", UUID.class);
            profiles.put(id, new Preparation(row.getObject("station_id", UUID.class), row.getString("station_name"),
                    row.getInt("duration_seconds"), row.getObject("upper_duration_seconds", Integer.class),
                    row.getLong("version"), List.copyOf(options.getOrDefault(id, List.of()))));
            return id;
        }).list();
        return profiles;
    }

    Preparation save(UUID dish, PreparationInput input, List<Customization> groups, Preparation current, String actor) {
        var choices = new HashSet<OptionKey>();
        for (var group : groups) {
            UUID groupId = orderingId(group.id(), dish + "/group/");
            for (var option : group.choices()) {
                choices.add(new OptionKey(groupId, orderingId(option.id(), groupId + "/option/")));
            }
        }
        if (input == null) {
            if (current != null && current.options().stream()
                    .anyMatch(option -> !choices.contains(new OptionKey(option.groupId(), option.optionId())))) {
                throw bad("Update the affected option workloads in Edit Dish.");
            }
            return current;
        }
        if (input.upperDurationSeconds() != null && input.upperDurationSeconds() < input.durationSeconds()) {
            throw bad("Upper duration must be at least the base duration.");
        }
        String station = jdbc.sql("SELECT name FROM kitchen_station WHERE id=:id")
                .param("id", input.stationId()).query(String.class).optional()
                .orElseThrow(() -> bad("Choose an existing preparation station."));
        var options = new ArrayList<OptionWork>();
        var used = new HashSet<OptionKey>();
        for (var option : input.options()) {
            UUID group = orderingId(option.groupId(), dish + "/group/");
            UUID choice = orderingId(option.optionId(), group + "/option/");
            var key = new OptionKey(group, choice);
            if (!choices.contains(key) || !used.add(key) || option.upperSeconds() < option.lowerSeconds()) {
                throw bad("Use one valid lower and upper workload for each option in this dish.");
            }
            options.add(new OptionWork(group, choice, option.lowerSeconds(), option.upperSeconds()));
        }
        // The calling catalog transaction holds the catalog lock and has flushed a newly created dish.
        long version = jdbc.sql("""
                INSERT INTO kitchen_prep_profile(menu_item_id,station_id,duration_seconds,upper_duration_seconds)
                VALUES (:id,:station,:lower,:upper)
                ON CONFLICT (menu_item_id) DO UPDATE SET station_id=EXCLUDED.station_id,
                    duration_seconds=EXCLUDED.duration_seconds, upper_duration_seconds=EXCLUDED.upper_duration_seconds,
                    version=kitchen_prep_profile.version+1
                RETURNING version
                """).param("id", dish).param("station", input.stationId()).param("lower", input.durationSeconds())
                .param("upper", input.upperDurationSeconds()).query(Long.class).single();
        jdbc.sql("DELETE FROM kitchen_option_workload WHERE menu_item_id=:id").param("id", dish).update();
        for (var option : options) {
            jdbc.sql("""
                    INSERT INTO kitchen_option_workload(menu_item_id,group_id,option_id,lower_seconds,upper_seconds)
                    VALUES (:id,:group,:option,:lower,:upper)
                    """).param("id", dish).param("group", option.groupId()).param("option", option.optionId())
                    .param("lower", option.lowerSeconds()).param("upper", option.upperSeconds()).update();
        }
        jdbc.sql("""
                INSERT INTO kitchen_configuration_audit(entity,entity_id,version,manager_username)
                VALUES ('PREP_PROFILE',:id,:version,:actor)
                """).param("id", dish).param("version", version).param("actor", actor).update();
        return new Preparation(input.stationId(), station, input.durationSeconds(), input.upperDurationSeconds(),
                version, List.copyOf(options));
    }

    private record OptionKey(UUID group, UUID option) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
