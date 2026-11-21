package com.roseroots.server.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import static com.roseroots.server.settings.BrandingData.Brand;

@Entity
@Table(name = "restaurant_settings_audit")
class RestaurantSettingsAudit {
    @Id UUID id;
    String actor;
    Instant changedAt;
    @JdbcTypeCode(SqlTypes.JSON) Brand beforeSettings;
    @JdbcTypeCode(SqlTypes.JSON) Brand afterSettings;

    protected RestaurantSettingsAudit() {}

    RestaurantSettingsAudit(String actor, Brand before, Brand after) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.changedAt = Instant.now();
        this.beforeSettings = before;
        this.afterSettings = after;
    }
}
