package com.hotel.workload.workload;

/**
 * The workload of one DEPARTMENT: its assigned workload against its working capacity.
 *
 * <ul>
 *   <li>{@code assignedMinutes}: the sum of the estimated duration over the department's ACTIVE assignment rows, held by its own staff (the
 *       existing rule: each assignee carries the task's full duration, so three assignees on a 60 minute task are 180 minutes).</li>
 *   <li>{@code capacityMinutes}: 480 minutes for each eligible staff member (Staff role, active, AVAILABLE, in the department: the Admin
 *       Dashboard's rule). {@code eligibleStaffCount} is that number.</li>
 *   <li>{@code utilizationPercent}: assignedMinutes / capacityMinutes x 100, one decimal; null when there is no capacity.</li>
 *   <li>{@code status}: the department thresholds (see {@link DepartmentWorkloadStatus}); NO_CAPACITY when there is no capacity.</li>
 *   <li>{@code activeTaskCount}: unique active tasks that have an assignment of the department's own staff; {@code staffCount}: distinct
 *       such staff members.</li>
 *   <li>{@code unassignedTaskCount} / {@code unassignedMinutes}: the department's active tasks nobody holds. A backlog, kept apart from the
 *       assigned workload and not part of the utilization.</li>
 * </ul>
 * The individual staff thresholds are not applied to a department.
 */
public class DepartmentWorkloadDto {
    private final String code;
    private final String name;
    private final long assignedMinutes;
    private final long capacityMinutes;
    private final int eligibleStaffCount;
    private final Double utilizationPercent;
    private final DepartmentWorkloadStatus status;
    private final int activeTaskCount;
    private final int staffCount;
    private final int unassignedTaskCount;
    private final long unassignedMinutes;

    public DepartmentWorkloadDto(String code, String name, long assignedMinutes, int eligibleStaffCount, int activeTaskCount, int staffCount,
                                 int unassignedTaskCount, long unassignedMinutes) {
        this.code = code;
        this.name = name;
        this.assignedMinutes = assignedMinutes;
        this.eligibleStaffCount = eligibleStaffCount;
        this.capacityMinutes = (long) eligibleStaffCount * SHIFT_MINUTES;
        this.utilizationPercent = capacityMinutes > 0 ? Math.round(assignedMinutes * 1000.0 / capacityMinutes) / 10.0 : null;
        this.status = DepartmentWorkloadStatus.derive(assignedMinutes, capacityMinutes);
        this.activeTaskCount = activeTaskCount;
        this.staffCount = staffCount;
        this.unassignedTaskCount = unassignedTaskCount;
        this.unassignedMinutes = unassignedMinutes;
    }

    /** The shift length one eligible staff member contributes to the department's capacity (the same 480 minute baseline as the staff view). */
    public static final int SHIFT_MINUTES = 480;

    public String getCode() { return code; }
    public String getName() { return name; }
    public long getAssignedMinutes() { return assignedMinutes; }
    public long getCapacityMinutes() { return capacityMinutes; }
    public int getEligibleStaffCount() { return eligibleStaffCount; }
    public Double getUtilizationPercent() { return utilizationPercent; }
    public DepartmentWorkloadStatus getStatus() { return status; }
    public int getActiveTaskCount() { return activeTaskCount; }
    public int getStaffCount() { return staffCount; }
    public int getUnassignedTaskCount() { return unassignedTaskCount; }
    public long getUnassignedMinutes() { return unassignedMinutes; }
}
