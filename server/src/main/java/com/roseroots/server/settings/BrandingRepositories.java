package com.roseroots.server.settings;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RestaurantSettingsRepository extends JpaRepository<RestaurantSettings, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settings from RestaurantSettings settings where settings.id = 1")
    Optional<RestaurantSettings> lockCurrent();
}

interface RestaurantSettingsAuditRepository extends JpaRepository<RestaurantSettingsAudit, UUID> {}
interface RestaurantLogoRepository extends JpaRepository<RestaurantLogo, UUID> {}
