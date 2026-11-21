package com.roseroots.server.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {
    @Query(value = "SELECT id FROM catalog_write_lock WHERE id = 1 FOR UPDATE", nativeQuery = true)
    int lockCatalog();
}

interface DishRepository extends JpaRepository<DishEntity, UUID> {
    long countByCategoryId(UUID categoryId);
}

interface DishAuditRepository extends JpaRepository<DishAudit, UUID> {}

interface MenuImageRepository extends JpaRepository<MenuImage, UUID> {
    java.util.Optional<MenuImage> findBySourceSha256(String sourceSha256);
}
