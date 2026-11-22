package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PreparationProfiles {
    record Seconds(long lower, long upper) {}

    record Profile(UUID menu, UUID station, int lower, Integer upper, JsonNode options) {
        Seconds workload(OrderingPricing.Line line) {
            if (upper == null || !line.ingredientsKnown()) return null;
            long best = lower;
            long worst = upper;
            for (JsonNode option : line.pricing().path("options")) {
                var duration = options.path(option.path("groupId").asText() + "/" + option.path("optionId").asText());
                if (duration.isMissingNode()) return null;
                best += duration.get(0).asLong();
                worst += duration.get(1).asLong();
            }
            int quantity = line.pricing().path("quantity").asInt();
            return new Seconds(best * quantity, worst * quantity);
        }
    }

    static Map<UUID, Profile> load(JdbcClient jdbc, List<OrderingPricing.Line> lines) {
        return jdbc.sql("""
                SELECT p.menu_item_id, p.station_id, p.duration_seconds, p.upper_duration_seconds,
                    coalesce((SELECT jsonb_object_agg(w.group_id::text || '/' || w.option_id::text,
                                      jsonb_build_array(w.lower_seconds, w.upper_seconds))
                              FROM kitchen_option_workload w WHERE w.menu_item_id = p.menu_item_id), '{}'::jsonb) AS options
                FROM kitchen_prep_profile p WHERE p.menu_item_id IN (:items)
                """).param("items", lines.stream().map(OrderingPricing.Line::menuItemId).distinct().toList())
                .query((row, index) -> new Profile(row.getObject("menu_item_id", UUID.class),
                        row.getObject("station_id", UUID.class), row.getInt("duration_seconds"),
                        row.getObject("upper_duration_seconds", Integer.class), OrderingJson.parse(row.getString("options"))))
                .list().stream().collect(Collectors.toMap(Profile::menu, Function.identity()));
    }

    private PreparationProfiles() {}
}
