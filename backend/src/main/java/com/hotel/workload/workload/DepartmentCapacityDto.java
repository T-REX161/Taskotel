package com.hotel.workload.workload;

import java.util.List;

/**
 * Capacity-based department pressure for the departments the caller may see (see {@link DepartmentCapacity} for the definitions).
 *
 * @param enabled                  the Max Task Capacity Alert switch: while off no department is AT RISK
 * @param capacityThresholdPercent the saved Capacity Threshold: the utilization at or above which a department is AT RISK
 * @param shiftMinutes             the capacity of one eligible person, in minutes (the standard shift)
 * @param departments              one entry per department in the caller's scope
 */
public record DepartmentCapacityDto(boolean enabled, int capacityThresholdPercent, int shiftMinutes, List<Entry> departments) {

    /**
     * @param remainingMinutes   estimated minutes of the department's active tasks, assigned and waiting (assignedMinutes + waitingMinutes);
     *                           falls only when tasks are completed, cancelled or re-estimated, never because a helper joins
     * @param assignedMinutes    the part held by someone (a supported task counts here for the department being helped)
     * @param waitingMinutes     the part nobody holds yet
     * @param activeTasks        active tasks of the department, held or waiting
     * @param waitingTasks       active tasks nobody holds yet
     * @param eligibleStaff      people whose capacity counts for this department now: its own eligible people who are not lent out, plus helpers
     * @param helpersIn          of those, people from another department on temporary support here
     * @param lentOut            eligible people of this department currently helping another one (their capacity is not counted here)
     * @param capacityMinutes    eligibleStaff x shiftMinutes
     * @param utilizationPercent remainingMinutes / capacityMinutes x 100 to one decimal; null when there is work but no capacity
     * @param level              LOW, NORMAL, HIGH, or AT_RISK (only while the alert is on)
     * @param atRisk             level == AT_RISK
     * @param noCapacity         there is work but nobody eligible to do it
     */
    public record Entry(String code, String name, int remainingMinutes, int assignedMinutes, int waitingMinutes, int activeTasks, int waitingTasks,
                        int eligibleStaff, int helpersIn, int lentOut, long capacityMinutes, Double utilizationPercent, WorkloadStatus level,
                        boolean atRisk, boolean noCapacity) {}
}
