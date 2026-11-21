package com.roseroots.server.catalog;

import jakarta.persistence.*;
import java.util.UUID;
import static com.roseroots.server.catalog.CatalogData.*;

@Entity
@Table(name = "catalog_category")
class CategoryEntity {
    @Id UUID id;
    UUID parentId;
    String name;
    int displayOrder;
    @Enumerated(EnumType.STRING) CategoryStatus status;

    protected CategoryEntity() {}

    Category toView(long itemCount) {
        return new Category(id, parentId, name, displayOrder, status, itemCount);
    }
}
