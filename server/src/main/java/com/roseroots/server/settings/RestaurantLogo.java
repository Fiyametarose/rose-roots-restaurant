package com.roseroots.server.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "restaurant_logo")
class RestaurantLogo {
    @Id UUID id;
    String storageReference;
    int width;
    int height;
    long fileSize;
    Instant createdAt;
    String createdBy;

    protected RestaurantLogo() {}

    RestaurantLogo(UUID id, int width, int height, long fileSize, String actor) {
        this.id = id;
        storageReference = id + ".png";
        this.width = width;
        this.height = height;
        this.fileSize = fileSize;
        createdAt = Instant.now();
        createdBy = actor;
    }
}
