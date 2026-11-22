package com.roseroots.server.catalog;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("catalog")
class CatalogTranslations {
    enum Target { CATEGORY, DISH }
    record Translation(Target targetType, UUID targetId, String locale, String name, String description, long version) {}
    record Published(List<Translation> translations) {}
    record Edit(Target targetType, UUID targetId, String locale, String name, String description, Long expectedVersion) {}

    private final CatalogService catalog;
    private final JdbcClient jdbc;

    CatalogTranslations(CatalogService catalog, JdbcClient jdbc) { this.catalog = catalog; this.jdbc = jdbc; }

    @GetMapping("/api/v1/menu-translations")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<Published> published() {
        var menu = catalog.snapshot(true);
        var dishes = menu.items().stream().map(CatalogData.Dish::id).collect(Collectors.toSet());
        var categories = menu.categories().stream().map(CatalogData.Category::id).collect(Collectors.toSet());
        var visible = list().stream().filter(row -> (row.targetType() == Target.DISH ? dishes : categories).contains(row.targetId())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(new Published(visible));
    }

    @GetMapping("/api/v1/admin/translations")
    ResponseEntity<List<Translation>> management() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(list());
    }

    @PatchMapping("/api/v1/admin/translations")
    @Transactional
    public ResponseEntity<Translation> save(@RequestBody Edit edit, Principal manager) {
        if (edit.targetType() == null || edit.targetId() == null || !("am".equals(edit.locale()) || "ar".equals(edit.locale()))
                || edit.expectedVersion() == null || edit.expectedVersion() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a catalog entry, language and current version.");
        }
        String name = clean(edit.name(), 120);
        String description = clean(edit.description(), 1500);
        if (edit.targetType() == Target.CATEGORY && !description.isEmpty()) throw badText();
        var menu = catalog.snapshot(false);
        boolean exists = edit.targetType() == Target.DISH ? menu.items().stream().anyMatch(row -> row.id().equals(edit.targetId()))
                : menu.categories().stream().anyMatch(row -> row.id().equals(edit.targetId()));
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog entry unavailable.");
        var statement = edit.expectedVersion() == 0 ? jdbc.sql("""
                INSERT INTO catalog_translation(target_type,target_id,locale,name,description)
                VALUES (:type,:id,:locale,:name,:description) ON CONFLICT DO NOTHING RETURNING version
                """) : jdbc.sql("""
                UPDATE catalog_translation SET name=:name,description=:description,version=version+1
                WHERE target_type=:type AND target_id=:id AND locale=:locale AND version=:expected RETURNING version
                """).param("expected", edit.expectedVersion());
        var version = statement.param("type", edit.targetType().name()).param("id", edit.targetId()).param("locale", edit.locale())
                .param("name", name).param("description", description).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Reload the latest translation before saving."));
        jdbc.sql("""
                INSERT INTO catalog_translation_audit(target_type,target_id,locale,version,manager_username,name,description)
                VALUES (:type,:id,:locale,:version,:actor,:name,:description)
                """).param("type", edit.targetType().name()).param("id", edit.targetId()).param("locale", edit.locale())
                .param("version", version).param("actor", manager.getName()).param("name", name).param("description", description).update();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Translation(edit.targetType(), edit.targetId(),
                edit.locale(), name, description, version));
    }

    private List<Translation> list() {
        return jdbc.sql("SELECT * FROM catalog_translation ORDER BY target_type,target_id,locale")
                .query((row, index) -> new Translation(Target.valueOf(row.getString("target_type")), row.getObject("target_id", UUID.class),
                        row.getString("locale"), row.getString("name"), row.getString("description"), row.getLong("version"))).list();
    }

    private static String clean(String value, int max) {
        if (value == null || value.length() > max || value.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r')) {
            throw badText();
        }
        return value.strip();
    }

    private static ResponseStatusException badText() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid translation text."); }
}
