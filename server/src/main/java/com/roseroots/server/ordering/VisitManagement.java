package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.roseroots.server.ordering.OrderingController.body;
import static com.roseroots.server.ordering.OrderingController.response;
import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingProblem.*;

@RestController
@Profile("catalog")
@RequestMapping("/api/v1/admin")
class VisitManagement {
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    VisitManagement(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/tables")
    ResponseEntity<String> tables() {
        return rows("""
                SELECT jsonb_build_object('id',id,'displayNumber',display_number,'enabled',enabled,'version',version)::text
                FROM ordering_table ORDER BY display_number,id
                """);
    }

    @GetMapping("/terminals")
    ResponseEntity<String> terminals() {
        return rows("""
                SELECT jsonb_build_object('id',id,'label',label,'tableId',table_id,'enabled',enabled,'version',version)::text
                FROM ordering_terminal ORDER BY label,id
                """);
    }

    @GetMapping("/sessions")
    ResponseEntity<String> activeSessions() {
        return rows("""
                SELECT jsonb_build_object('id',s.id,'terminalId',s.terminal_id,'mode',s.mode,
                    'serviceReference',coalesce('Table '||s.table_number,s.pickup_reference),
                    'status',CASE WHEN s.expires_at <= now() THEN 'EXPIRED' ELSE s.status END,
                    'billVersion',s.bill_version,'createdAt',s.created_at,'expiresAt',s.expires_at,
                    'outstandingMinor',coalesce(o.payable,0)-coalesce(p.paid,0),
                    'paymentPending',coalesce(p.pending,false))::text
                FROM dining_session s
                LEFT JOIN LATERAL (SELECT sum(payable_minor) AS payable FROM accepted_order WHERE session_id=s.id) o ON true
                LEFT JOIN LATERAL (SELECT sum(amount_minor) FILTER(WHERE status='SUCCEEDED') AS paid,
                    bool_or(status='PENDING') AS pending FROM payment_attempt WHERE session_id=s.id) p ON true
                WHERE s.status <> 'CLOSED' ORDER BY s.created_at DESC LIMIT 250
                """);
    }

    @PostMapping("/tables")
    @Transactional
    public ResponseEntity<String> createTable(HttpServletRequest request, Principal manager) {
        var input = fields(body(request), "displayNumber");
        UUID id = UUID.randomUUID();
        String code = value(input, "displayNumber", 24);
        try {
            jdbc.sql("INSERT INTO ordering_table(id,restaurant_id,display_number) VALUES (:id,:restaurant,:number)")
                    .param("id", id).param("restaurant", restaurant()).param("number", code).update();
        } catch (DuplicateKeyException duplicate) { throw conflict("DUPLICATE_TABLE", "This table number already exists."); }
        return audit(201, "TABLE", id, 1, manager, object().put("displayNumber", code).put("enabled", true));
    }

    @PatchMapping("/tables/{id}")
    @Transactional
    public ResponseEntity<String> updateTable(@PathVariable("id") String table, HttpServletRequest request, Principal manager) {
        UUID id = id(table);
        var input = fields(body(request), "displayNumber", "enabled", "expectedVersion");
        boolean enabled = enabled(input);
        String code = value(input, "displayNumber", 24);
        long expected = integer(input, "expectedVersion", 1, MAX_SAFE);
        long updated;
        try {
            updated = jdbc.sql("""
                    UPDATE ordering_table SET display_number=:name,enabled=:enabled,version=version+1
                    WHERE id=:id AND version=:version RETURNING version
                    """).param("name", code).param("enabled", enabled).param("id", id).param("version", expected)
                    .query(Long.class).optional().orElseThrow(VisitManagement::stale);
        } catch (DuplicateKeyException duplicate) { throw conflict("DUPLICATE_TABLE", "This table number already exists."); }
        return audit(200, "TABLE", id, updated, manager, object().put("displayNumber", code).put("enabled", enabled));
    }

    @PostMapping("/terminals")
    @Transactional
    public ResponseEntity<String> createTerminal(HttpServletRequest request, Principal manager) {
        var input = body(request);
        UUID restaurant = restaurant();
        UUID table = assignment(input, restaurant, false, null);
        UUID terminal = UUID.randomUUID();
        String name = value(input, "label", 80);
        jdbc.sql("INSERT INTO ordering_terminal(id,restaurant_id,label,table_id) VALUES (:id,:restaurant,:label,:table)")
                .param("id", terminal).param("restaurant", restaurant).param("label", name).param("table", table).update();
        byte[] material = new byte[32];
        random.nextBytes(material);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        jdbc.sql("""
                INSERT INTO ordering_credential(id,restaurant_id,terminal_id,scope,token_sha256,expires_at)
                VALUES (:id,:restaurant,:terminal,'TERMINAL',:hash,now()+interval '90 days')
                """).param("id", UUID.randomUUID()).param("restaurant", restaurant).param("terminal", terminal)
                .param("hash", digest(token)).update();
        var data = terminalData(name, table, true);
        record("TERMINAL", terminal, 1, manager, data);
        return response(201, data.put("id", terminal.toString()).put("version", 1).put("bearerToken", token));
    }

    @PatchMapping("/terminals/{id}")
    @Transactional
    public ResponseEntity<String> updateTerminal(@PathVariable("id") String value, HttpServletRequest request, Principal manager) {
        UUID terminal = id(value);
        var input = body(request);
        UUID restaurant = restaurant();
        var current = jdbc.sql("SELECT table_id,enabled,version FROM ordering_terminal WHERE id=:id FOR UPDATE")
                .param("id", terminal).query((row, index) -> new Existing(row.getObject(1, UUID.class),row.getBoolean(2),row.getLong(3)))
                .optional().orElseThrow(OrderingProblem::missing);
        UUID table = assignment(input, restaurant, true, current.table());
        boolean enabled = enabled(input);
        long expected = integer(input, "expectedVersion", 1, MAX_SAFE);
        if (expected != current.version()) throw stale();
        if ((!enabled && current.enabled() || !java.util.Objects.equals(table, current.table())) && currentVisits(terminal)) {
            throw conflict("ACTIVE_SESSION", "Close this terminal's active or unsettled visits before changing its assignment or disabling it.");
        }
        String name = value(input, "label", 80);
        jdbc.sql("UPDATE ordering_terminal SET label=:label,table_id=:table,enabled=:enabled,version=version+1 WHERE id=:id")
                .param("label", name).param("table", table).param("enabled", enabled).param("id", terminal).update();
        return audit(200, "TERMINAL", terminal, expected + 1, manager, terminalData(name, table, enabled));
    }

    private record Existing(UUID table, boolean enabled, long version) {}

    private boolean currentVisits(UUID terminal) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM dining_session s WHERE terminal_id=:terminal AND status <> 'CLOSED'
                AND (expires_at>now() OR EXISTS(SELECT 1 FROM payment_attempt p WHERE p.session_id=s.id AND p.status='PENDING')
                  OR coalesce((SELECT sum(payable_minor) FROM accepted_order WHERE session_id=s.id),0) >
                     coalesce((SELECT sum(amount_minor) FROM payment_attempt WHERE session_id=s.id AND status='SUCCEEDED'),0)))
                """).param("terminal", terminal).query(Boolean.class).single();
    }

    private UUID assignment(JsonNode input, UUID restaurant, boolean update, UUID existing) {
        if (!input.isObject() || !input.has("tableId")) throw OrderingProblem.bad("Specify a table ID or null.");
        var fixed = ((ObjectNode) input).deepCopy();
        fixed.remove("tableId");
        if (update) fields(fixed, "label", "enabled", "expectedVersion"); else fields(fixed, "label");
        if (input.path("tableId").isNull()) return null;
        UUID table = id(input, "tableId");
        if (table.equals(existing)) return table;
        return jdbc.sql("SELECT id FROM ordering_table WHERE id=:id AND restaurant_id=:restaurant AND enabled FOR SHARE")
                .param("id", table).param("restaurant", restaurant).query(UUID.class).optional()
                .orElseThrow(() -> conflict("DEVICE_UNASSIGNED", "Choose an enabled restaurant table."));
    }

    private UUID restaurant() {
        jdbc.sql("INSERT INTO ordering_restaurant(id) VALUES (:id) ON CONFLICT(singleton) DO NOTHING")
                .param("id", UUID.randomUUID()).update();
        return jdbc.sql("SELECT id FROM ordering_restaurant").query(UUID.class).single();
    }

    private ResponseEntity<String> rows(String sql) {
        var result = array();
        jdbc.sql(sql).query(String.class).list().forEach(raw -> result.add(parse(raw)));
        return response(200, result);
    }

    private ResponseEntity<String> audit(int status, String type, UUID id, long version, Principal manager, ObjectNode data) {
        record(type, id, version, manager, data);
        return response(status, data.put("id", id.toString()).put("version", version));
    }

    private void record(String type, UUID id, long version, Principal manager, ObjectNode data) {
        jdbc.sql("""
                INSERT INTO ordering_device_audit(resource,resource_id,version,manager_username,change)
                VALUES (:type,:id,:version,:manager,CAST(:change AS jsonb))
                """).param("type", type).param("id", id).param("version", version)
                .param("manager", manager.getName()).param("change", data.toString()).update();
    }

    private static ObjectNode terminalData(String name, UUID table, boolean enabled) {
        return object().put("label", name).put("tableId", table == null ? null : table.toString()).put("enabled", enabled);
    }

    private static String value(JsonNode input, String name, int length) {
        String value = text(input, name, length).strip();
        if (value.isEmpty()) throw OrderingProblem.bad("Enter a nonempty " + name + ".");
        return value;
    }

    private static boolean enabled(JsonNode input) {
        if (!input.path("enabled").isBoolean()) throw OrderingProblem.bad("Enabled must be a boolean.");
        return input.get("enabled").asBoolean();
    }

    private static OrderingProblem stale() { return conflict("VERSION_CONFLICT", "Reload the table or terminal and retry."); }

    @ExceptionHandler(OrderingProblem.class)
    ResponseEntity<String> problem(OrderingProblem error) {
        return ResponseEntity.status(error.status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(error.body().toString());
    }
}
