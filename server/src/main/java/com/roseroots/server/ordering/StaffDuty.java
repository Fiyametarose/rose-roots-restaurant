package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.roseroots.server.ordering.OrderingController.*;
import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;
import static com.roseroots.server.ordering.OrderingProblem.*;

@RestController
@Profile("catalog")
@RequestMapping("/api/v1")
class StaffDuty {
    private final JdbcClient jdbc;

    StaffDuty(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/kitchen/staff-duty")
    ResponseEntity<String> self(@AuthenticationPrincipal Actor actor) {
        return response(200, read(actor.staffId()));
    }

    @PatchMapping("/kitchen/staff-duty")
    @Transactional
    public ResponseEntity<String> update(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        var input = fields(body(request), "hours", "expectedVersion");
        long hours = integer(input, "hours", 0, 12);
        if (hours != 0 && hours != 4 && hours != 8 && hours != 12) throw bad("Choose a 4, 8 or 12 hour shift.");
        return response(200, change(actor.staffId(), integer(input, "expectedVersion", 1, MAX_SAFE), hours,
                actor.staffId().toString(), hours == 0 ? "END" : "START"));
    }

    @GetMapping("/admin/service-duty")
    ResponseEntity<String> overview() {
        var rows = array();
        jdbc.sql("SELECT id FROM staff_account WHERE staff_role='WAITER' ORDER BY username")
                .query(UUID.class).list().forEach(staff -> rows.add(read(staff)));
        return response(200, rows);
    }

    @PatchMapping("/admin/service-duty/{staff}")
    @Transactional
    public ResponseEntity<String> assign(@PathVariable("staff") String staff, HttpServletRequest request, Principal manager) {
        UUID account = id(staff);
        var input = fields(body(request), "tableIds", "expectedVersion");
        var tables = new java.util.LinkedHashSet<UUID>();
        for (var value : list(input, "tableIds", 0, 200)) {
            if (!value.isTextual() || !tables.add(id(value.asText()))) throw bad("Select distinct tables.");
        }
        jdbc.sql("""
                UPDATE staff_account SET version=version+1
                WHERE id=:staff AND staff_role='WAITER' AND archived_at IS NULL AND version=:version
                RETURNING id
                """).param("staff", account).param("version", integer(input, "expectedVersion", 1, MAX_SAFE))
                .query(UUID.class).optional().orElseThrow(StaffDuty::stale);
        jdbc.sql("DELETE FROM waiter_table_assignment WHERE staff_id=:staff").param("staff", account).update();
        for (UUID table : tables) {
            if (jdbc.sql("""
                    INSERT INTO waiter_table_assignment(staff_id,table_id)
                    SELECT :staff,t.id FROM ordering_table t JOIN ordering_credential c ON c.restaurant_id=t.restaurant_id
                    JOIN staff_account a ON a.credential_id=c.id WHERE a.id=:staff AND t.id=:table AND t.enabled
                    """).param("staff", account).param("table", table).update() != 1) throw bad("Choose an enabled restaurant table.");
        }
        jdbc.sql("""
                INSERT INTO kitchen_configuration_audit(entity,entity_id,version,manager_username)
                SELECT 'WAITER_ASSIGNMENT',id,version,:manager FROM staff_account WHERE id=:staff
                """).param("staff", account).param("manager", manager.getName()).update();
        return response(200, read(account));
    }

    @PostMapping("/admin/staff-duty/{staff}/end")
    @Transactional
    public ResponseEntity<String> emergencyEnd(@PathVariable("staff") String staff, Principal manager, HttpServletRequest request) {
        var input = fields(body(request), "expectedVersion");
        return response(200, change(id(staff), integer(input, "expectedVersion", 1, MAX_SAFE), 0,
                manager.getName(), "EMERGENCY_END"));
    }

    private ObjectNode change(UUID staff, long version, long hours, String actor, String action) {
        var current = jdbc.sql("SELECT id FROM staff_account WHERE id=:id FOR UPDATE")
                .param("id", staff).query(UUID.class).optional().orElseThrow(OrderingProblem::missing);
        var snapshot = read(current);
        if (snapshot.path("version").asLong() != version) throw stale();
        if (snapshot.path("onDuty").asBoolean() == (hours > 0)) return snapshot;
        jdbc.sql("""
                UPDATE staff_account SET version=version+1,
                    shift_started_at=CASE WHEN :hours>0 THEN now() ELSE shift_started_at END,
                    available_until=CASE WHEN :hours>0 THEN now()+make_interval(hours=>:hours) ELSE now() END
                WHERE id=:staff AND enabled AND archived_at IS NULL AND NOT password_reset_required
                  AND staff_role IN ('CHEF','WAITER') AND EXISTS(SELECT 1 FROM ordering_credential c
                    WHERE c.id=credential_id AND c.revoked_at IS NULL AND c.expires_at>now())
                RETURNING version
                """).param("staff", staff).param("hours", (int) hours).query(Long.class).optional().orElseThrow(StaffDuty::stale);
        jdbc.sql("""
                INSERT INTO staff_duty_audit(staff_id,actor,action,version,shift_ends_at)
                SELECT id,:actor,:action,version,available_until FROM staff_account WHERE id=:staff
                """).param("staff", staff).param("actor", actor).param("action", action).update();
        return read(staff);
    }

    private ObjectNode read(UUID staff) {
        return (ObjectNode) jdbc.sql("""
                SELECT jsonb_build_object('id',a.id,'username',a.username,'role',a.staff_role,'version',a.version,
                  'onDuty',a.enabled AND NOT a.password_reset_required AND coalesce(a.available_until>now(),false),
                  'enabled',a.enabled,'archived',a.archived_at IS NOT NULL,
                  'shiftStartedAt',a.shift_started_at,'availableUntil',a.available_until,'serverTime',now(),
                  'stations',coalesce((SELECT jsonb_agg(s.name ORDER BY s.name) FROM kitchen_station s
                     JOIN kitchen_station_chef x ON x.station_id=s.id WHERE x.chef_id=a.id AND s.active),'[]'::jsonb),
                  'tables',coalesce((SELECT jsonb_agg(t.display_number ORDER BY t.display_number) FROM ordering_table t
                     JOIN waiter_table_assignment x ON x.table_id=t.id WHERE x.staff_id=a.id),'[]'::jsonb),
                  'tableIds',coalesce((SELECT jsonb_agg(x.table_id) FROM waiter_table_assignment x WHERE x.staff_id=a.id),'[]'::jsonb))::text
                FROM staff_account a WHERE a.id=:staff
                """).param("staff", staff).query(String.class).optional().map(OrderingJson::parse)
                .orElseThrow(OrderingProblem::missing);
    }

    static void requireWaiter(JdbcClient jdbc, UUID waiter, UUID restaurant, UUID session) {
        if (!jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM staff_account a JOIN ordering_credential c ON c.id=a.credential_id
                  JOIN dining_session s ON s.id=:session AND s.restaurant_id=c.restaurant_id
                  WHERE a.id=:waiter AND c.restaurant_id=:restaurant AND a.staff_role='WAITER'
                    AND a.enabled AND NOT a.password_reset_required AND a.available_until>now()
                    AND c.revoked_at IS NULL AND c.expires_at>now()
                    AND (NOT EXISTS(SELECT 1 FROM waiter_table_assignment WHERE staff_id=a.id)
                      OR EXISTS(SELECT 1 FROM waiter_table_assignment WHERE staff_id=a.id AND table_id=s.table_id))
                  FOR SHARE OF a)
                """).param("waiter", waiter).param("restaurant", restaurant).param("session", session)
                .query(Boolean.class).single()) {
            throw conflict("WAITER_NOT_AVAILABLE", "An on-duty Waiter assigned to this visit is required.");
        }
    }

    @ExceptionHandler(OrderingProblem.class)
    ResponseEntity<String> error(OrderingProblem problem) { return ResponseEntity.status(problem.status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(problem.body().toString()); }

    private static OrderingProblem stale() { return conflict("VERSION_CONFLICT", "Staff availability or assignments changed. Reload and try again."); }
}
