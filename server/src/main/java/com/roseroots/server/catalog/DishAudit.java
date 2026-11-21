package com.roseroots.server.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static com.roseroots.server.catalog.CatalogData.*;

@Entity
@Table(name = "catalog_item_audit")
class DishAudit {
    @Id UUID id;
    UUID itemId;
    String actor;
    Instant changedAt;
    BigDecimal previousPrice;
    BigDecimal price;
    @Enumerated(EnumType.STRING) Availability previousAvailability;
    @Enumerated(EnumType.STRING) Availability availability;
    boolean previouslyArchived;
    boolean archived;
    String reason;

    protected DishAudit() {}

    DishAudit(Dish before, DishEntity after, String actor, String reason) {
        id = UUID.randomUUID();
        itemId = after.id;
        this.actor = actor;
        changedAt = Instant.now();
        previousPrice = before.price();
        price = after.price;
        previousAvailability = before.availability();
        availability = after.availability;
        previouslyArchived = before.archived();
        archived = after.archived;
        this.reason = reason;
    }
}
