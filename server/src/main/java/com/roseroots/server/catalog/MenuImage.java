package com.roseroots.server.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_menu_image")
class MenuImage {
    @Id UUID id;
    String storageReference;
    String contentType;
    int width;
    int height;
    long fileSize;
    Instant createdAt;
    String createdBy;
    String sourceSha256;

    protected MenuImage() {}

    MenuImage(UUID id, int width, int height, long fileSize, String actor) {
        this.id = id;
        storageReference = id + ".jpg";
        contentType = "image/jpeg";
        this.width = width;
        this.height = height;
        this.fileSize = fileSize;
        createdAt = Instant.now();
        createdBy = actor;
    }
}
