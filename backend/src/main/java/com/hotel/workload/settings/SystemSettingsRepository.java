package com.hotel.workload.settings;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SystemSettingsRepository extends JpaRepository<SystemSettings, UUID> {

    /** The global row, if it exists. */
    Optional<SystemSettings> findBySettingsKey(String settingsKey);

    /** The global row, locked until the transaction ends, so two Admins saving at once are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SystemSettings s WHERE s.settingsKey = :key")
    Optional<SystemSettings> lockBySettingsKey(@Param("key") String key);
}
