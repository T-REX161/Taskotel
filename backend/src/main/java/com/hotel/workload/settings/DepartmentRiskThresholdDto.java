package com.hotel.workload.settings;

/** The Capacity Threshold as the Workload page needs it: only the alert switch and the percentage, nothing else from System Settings. */
public record DepartmentRiskThresholdDto(boolean enabled, int capacityThresholdPercent) {}
