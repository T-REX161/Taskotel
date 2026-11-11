package com.hotel.workload.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The application's one set of system-wide settings (Settings, System Settings; Admin only).
 *
 * <p>There is at most ONE row: {@code settings_key} is always {@value #GLOBAL_KEY} and is unique (migration V17 also checks it), so a
 * second row cannot exist. The row is created by the first successful update; until then {@link SystemSettingsService} reports the defaults
 * below, so no row has to be seeded and nothing existing is touched.
 */
@Entity
@Table(name = "system_settings", uniqueConstraints = @UniqueConstraint(name = "uk_system_settings_key", columnNames = "settings_key"))
public class SystemSettings {

    public static final String GLOBAL_KEY = "GLOBAL";

    // The agreed defaults, used while no row exists
    public static final boolean DEFAULT_AUTO_BALANCING = true;
    public static final boolean DEFAULT_CAPACITY_ALERT = true;
    public static final boolean DEFAULT_CROSS_DEPARTMENT_ASSISTANCE = true;
    public static final int DEFAULT_CAPACITY_THRESHOLD_PERCENT = 85;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "settings_key", nullable = false, updatable = false, length = 20)
    private String settingsKey = GLOBAL_KEY;

    @Column(name = "auto_balancing_enabled", nullable = false)
    private boolean autoBalancingEnabled = DEFAULT_AUTO_BALANCING;

    @Column(name = "capacity_alert_enabled", nullable = false)
    private boolean capacityAlertEnabled = DEFAULT_CAPACITY_ALERT;

    @Column(name = "cross_department_assistance_enabled", nullable = false)
    private boolean crossDepartmentAssistanceEnabled = DEFAULT_CROSS_DEPARTMENT_ASSISTANCE;

    @Column(name = "capacity_threshold_percent", nullable = false)
    private int capacityThresholdPercent = DEFAULT_CAPACITY_THRESHOLD_PERCENT;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by_user_id", nullable = false)
    private UUID updatedByUserId;

    @Column(name = "updated_by_username", nullable = false)
    private String updatedByUsername;

    public SystemSettings() {}

    public UUID getId() { return id; }
    public String getSettingsKey() { return settingsKey; }
    public boolean isAutoBalancingEnabled() { return autoBalancingEnabled; }
    public void setAutoBalancingEnabled(boolean value) { this.autoBalancingEnabled = value; }
    public boolean isCapacityAlertEnabled() { return capacityAlertEnabled; }
    public void setCapacityAlertEnabled(boolean value) { this.capacityAlertEnabled = value; }
    public boolean isCrossDepartmentAssistanceEnabled() { return crossDepartmentAssistanceEnabled; }
    public void setCrossDepartmentAssistanceEnabled(boolean value) { this.crossDepartmentAssistanceEnabled = value; }
    public int getCapacityThresholdPercent() { return capacityThresholdPercent; }
    public void setCapacityThresholdPercent(int value) { this.capacityThresholdPercent = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public UUID getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(UUID updatedByUserId) { this.updatedByUserId = updatedByUserId; }
    public String getUpdatedByUsername() { return updatedByUsername; }
    public void setUpdatedByUsername(String updatedByUsername) { this.updatedByUsername = updatedByUsername; }
}
