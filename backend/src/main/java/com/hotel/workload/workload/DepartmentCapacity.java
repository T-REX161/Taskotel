package com.hotel.workload.workload;

/**
 * The one definition of department workload pressure, as pure functions (no database, no clock), so every screen and rule that talks about
 * "department risk" uses exactly this and it can be tested directly.
 *
 * <p><b>Utilization</b> = remaining task minutes / staff capacity minutes x 100, where
 * <ul>
 *   <li><b>remaining task minutes</b> are the estimated minutes of the department's active tasks (PENDING, ASSIGNED, IN_PROGRESS), assigned or
 *       still waiting. They fall only when tasks are completed, cancelled or re-estimated; moving a task from "waiting" to a helper does not
 *       change them, and neither does a helper joining;</li>
 *   <li><b>staff capacity</b> is the number of eligible people times one standard shift ({@link #STANDARD_SHIFT_MINUTES}). Eligible means an
 *       active STAFF account marked AVAILABLE with a department. A person on temporary cross-department support counts for the department
 *       they are helping and NOT for their home department, so nobody is counted twice.</li>
 * </ul>
 *
 * <p>Taskotel stores no shifts or working hours, so the capacity of one person is the application's existing standard shift of 480 minutes
 * (the same shift the individual workload bands are based on: 120, 240 and 360 minutes are 25%, 50% and 75% of it). Utilization can exceed
 * 100%: it then means more work than the available people can do in one standard shift.
 *
 * <p><b>Risk.</b> With the Max Task Capacity Alert switched on, a department is AT RISK when its utilization is at or above the saved Capacity
 * Threshold (a whole number from 1 to 100). The comparison is exact (integer arithmetic, no rounding). Below the threshold the existing bands
 * apply: LOW up to 25%, NORMAL up to 50%, HIGH above 50%. With the switch off no department is AT RISK. A department with work but nobody
 * eligible to do it has no capacity at all: it is AT RISK (when the alert is on) whatever the threshold.
 */
public final class DepartmentCapacity {

    /** The capacity of one eligible person: the application's standard shift, in minutes. */
    public static final int STANDARD_SHIFT_MINUTES = 480;

    /** The Capacity Threshold and the Max Task Capacity Alert switch, as the risk rule needs them. */
    public record Risk(boolean enabled, int thresholdPercent) {
        /** No risk rule at all (the alert is off): nothing is ever AT RISK. */
        public static final Risk OFF = new Risk(false, 100);
        public static Risk of(boolean enabled, int thresholdPercent) { return new Risk(enabled, thresholdPercent); }
    }

    private DepartmentCapacity() {}

    public static long capacityMinutes(int staff) {
        return staff <= 0 ? 0 : (long) staff * STANDARD_SHIFT_MINUTES;
    }

    /** True when there is work but nobody eligible to do it. */
    public static boolean noCapacity(long remainingMinutes, int staff) {
        return staff <= 0 && remainingMinutes > 0;
    }

    /** Utilization in percent rounded to one decimal, or null when there is no capacity (a percentage would be meaningless). */
    public static Double utilizationPercent(long remainingMinutes, int staff) {
        if (staff <= 0) return remainingMinutes > 0 ? null : 0.0;
        return Math.round(remainingMinutes * 1000.0 / capacityMinutes(staff)) / 10.0;
    }

    /** Exact test: is utilization at or above {@code thresholdPercent}? With no capacity, any work at all counts as at or above. */
    public static boolean atOrAbove(long remainingMinutes, int staff, int thresholdPercent) {
        if (staff <= 0) return remainingMinutes > 0;
        return remainingMinutes * 100 >= (long) thresholdPercent * capacityMinutes(staff);
    }

    /** AT RISK under the given rule (never while the alert is off). */
    public static boolean atRisk(long remainingMinutes, int staff, Risk risk) {
        return risk != null && risk.enabled() && atOrAbove(remainingMinutes, staff, risk.thresholdPercent());
    }

    /** The level shown for a department: AT_RISK (alert on and at or above the threshold), otherwise LOW (<= 25%), NORMAL (<= 50%) or HIGH. */
    public static WorkloadStatus level(long remainingMinutes, int staff, Risk risk) {
        if (atRisk(remainingMinutes, staff, risk)) return WorkloadStatus.AT_RISK;
        if (staff <= 0) return remainingMinutes > 0 ? WorkloadStatus.HIGH : WorkloadStatus.LOW;
        long capacity = capacityMinutes(staff);
        if (remainingMinutes * 100 <= 25 * capacity) return WorkloadStatus.LOW;
        if (remainingMinutes * 100 <= 50 * capacity) return WorkloadStatus.NORMAL;
        return WorkloadStatus.HIGH;
    }

    /** True when {@code staff} people can no longer carry the work without being at or above the limit: the donor safety test. */
    public static boolean exceedsLimit(long remainingMinutes, int staff, Risk risk) {
        if (risk != null && risk.enabled()) return atOrAbove(remainingMinutes, staff, risk.thresholdPercent());
        // No threshold to respect: the limit is simply a full shift per person (more than 100% would only move the overload elsewhere)
        if (staff <= 0) return remainingMinutes > 0;
        return remainingMinutes * 100 > 100L * capacityMinutes(staff);
    }

    /**
     * How many extra people the department needs for its utilization to drop below the threshold (0 when it is not AT RISK, or when the alert
     * is off). Always finite: enough people bring utilization under any threshold.
     */
    public static int helpersToLeaveRisk(long remainingMinutes, int staff, Risk risk) {
        if (!atRisk(remainingMinutes, staff, risk)) return 0;
        int extra = 0;
        while (extra < 10_000 && atOrAbove(remainingMinutes, Math.max(staff, 0) + extra, risk.thresholdPercent())) extra++;
        return extra;
    }
}
