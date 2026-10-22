package com.hotel.workload.workload;

/**
 * Represents the workload level of a staff member based on
 * the total estimated duration (in minutes) of their active tasks.
 *
 * Thresholds:
 *   LOW    →   0 – 120 minutes
 *   NORMAL → 121 – 240 minutes
 *   HIGH   → 241 – 360 minutes
 *   AT_RISK → > 360 minutes
 */
public enum WorkloadStatus {
    LOW,
    NORMAL,
    HIGH,
    AT_RISK
}
