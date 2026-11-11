package com.hotel.workload.settings;

import java.time.LocalDateTime;

/** The system settings as the API returns them. updatedAt and updatedBy are null while the defaults are in use (nothing saved yet). */
public record SystemSettingsDto(
        boolean autoBalancingEnabled,
        boolean capacityAlertEnabled,
        boolean crossDepartmentAssistanceEnabled,
        int capacityThresholdPercent,
        LocalDateTime updatedAt,
        String updatedBy) {

    static SystemSettingsDto defaults() {
        return new SystemSettingsDto(SystemSettings.DEFAULT_AUTO_BALANCING, SystemSettings.DEFAULT_CAPACITY_ALERT,
                SystemSettings.DEFAULT_CROSS_DEPARTMENT_ASSISTANCE, SystemSettings.DEFAULT_CAPACITY_THRESHOLD_PERCENT, null, null);
    }

    static SystemSettingsDto of(SystemSettings s) {
        return new SystemSettingsDto(s.isAutoBalancingEnabled(), s.isCapacityAlertEnabled(), s.isCrossDepartmentAssistanceEnabled(),
                s.getCapacityThresholdPercent(), s.getUpdatedAt(), s.getUpdatedByUsername());
    }
}
