package com.hotel.workload.workload;

/**
 * The status of a DEPARTMENT's workload utilization (assigned minutes over working capacity). Deliberately separate from {@link WorkloadStatus},
 * which classifies ONE person by minutes (LOW 0-120, NORMAL 121-240, HIGH 241-360, AT_RISK above 360) and must not be applied to a department.
 *
 * <pre>
 * LOW      0% - 25%
 * NORMAL   above 25% through 50%
 * HIGH     above 50% through 75%
 * AT_RISK  above 75%
 * NO_CAPACITY  the department has no eligible staff, so there is no capacity to measure against
 * </pre>
 */
public enum DepartmentWorkloadStatus {
    LOW, NORMAL, HIGH, AT_RISK, NO_CAPACITY;

    /**
     * Whole-number comparisons (minutes x 100 against percent x capacity), so a boundary is exact: exactly 25%, 50% and 75% belong to the lower
     * band. With no capacity there is no utilization, never a division by zero.
     */
    public static DepartmentWorkloadStatus derive(long assignedMinutes, long capacityMinutes) {
        if (capacityMinutes <= 0) return NO_CAPACITY;
        long scaled = Math.max(0, assignedMinutes) * 100;
        if (scaled <= 25 * capacityMinutes) return LOW;
        if (scaled <= 50 * capacityMinutes) return NORMAL;
        if (scaled <= 75 * capacityMinutes) return HIGH;
        return AT_RISK;
    }
}
