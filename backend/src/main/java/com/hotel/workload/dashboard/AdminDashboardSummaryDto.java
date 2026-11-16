package com.hotel.workload.dashboard;

public class AdminDashboardSummaryDto {

    /**
     * For a signed-in Manager only: the same figures per department the Manager manages, the primary department first (flagged
     * {@code primary}), then the additional ones by name. The totals of this response are the sums of these entries. Null, and so left out of
     * the response, for an Admin.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.List<DepartmentSummary> departmentBreakdown;

    /**
     * One managed department's figures, defined exactly like the response totals (staff counted as operational Staff of the department, tasks
     * by status, active assignments whose task and holder are both in the department, workload buckets from the Workload calculation).
     */
    public record DepartmentSummary(String departmentCode, String departmentName, boolean primary,
                                    long totalStaff, long availableStaff, long unavailableStaff, long inactiveStaff,
                                    long totalTasks, long pendingTasks, long assignedTasks, long inProgressTasks, long completedTasks,
                                    long cancelledTasks, long totalActiveAssignments,
                                    long totalStaffWithWorkload, long lowWorkloadStaff, long normalWorkloadStaff, long highWorkloadStaff,
                                    long atRiskStaff) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public java.util.List<DepartmentSummary> getDepartmentBreakdown() { return departmentBreakdown; }
    public void setDepartmentBreakdown(java.util.List<DepartmentSummary> departmentBreakdown) { this.departmentBreakdown = departmentBreakdown; }
    // Staff metrics
    private long totalStaff;
    private long availableStaff;
    private long unavailableStaff;
    private long inactiveStaff;

    // Task metrics
    private long totalTasks;
    private long pendingTasks;
    private long assignedTasks;
    private long inProgressTasks;
    private long completedTasks;
    private long cancelledTasks;

    // Assignment metrics
    private long totalActiveAssignments;

    // Workload metrics
    private long totalStaffWithWorkload;
    private long lowWorkloadStaff;
    private long normalWorkloadStaff;
    private long highWorkloadStaff;
    private long atRiskStaff;

    /**
     * Capacity warnings (System Settings: Max Task Capacity Alert and Capacity Threshold): one entry per department in the caller's scope
     * whose share of staff in work has reached the threshold. Always empty while the alert is switched off.
     */
    private java.util.List<CapacityAlert> capacityAlerts = new java.util.ArrayList<>();

    /**
     * staffInWork: eligible Staff of the department holding at least one active assignment (the numerator); operationalStaff: all eligible
     * Staff of the department (the denominator). Eligible = Staff role, active, AVAILABLE, with a department, the same population for both, so
     * percentInWork never exceeds 100. Managers, Administrators, inactive and unavailable Staff and Staff without a department are in neither.
     */
    public record CapacityAlert(String departmentCode, String departmentName, int staffInWork, int operationalStaff, int percentInWork, int thresholdPercent) {}

    public java.util.List<CapacityAlert> getCapacityAlerts() { return capacityAlerts; }
    public void setCapacityAlerts(java.util.List<CapacityAlert> capacityAlerts) { this.capacityAlerts = capacityAlerts; }

    // Getters and Setters
    public long getTotalStaff() { return totalStaff; }
    public void setTotalStaff(long totalStaff) { this.totalStaff = totalStaff; }
    
    public long getAvailableStaff() { return availableStaff; }
    public void setAvailableStaff(long availableStaff) { this.availableStaff = availableStaff; }
    
    public long getUnavailableStaff() { return unavailableStaff; }
    public void setUnavailableStaff(long unavailableStaff) { this.unavailableStaff = unavailableStaff; }
    
    public long getInactiveStaff() { return inactiveStaff; }
    public void setInactiveStaff(long inactiveStaff) { this.inactiveStaff = inactiveStaff; }

    public long getTotalTasks() { return totalTasks; }
    public void setTotalTasks(long totalTasks) { this.totalTasks = totalTasks; }
    
    public long getPendingTasks() { return pendingTasks; }
    public void setPendingTasks(long pendingTasks) { this.pendingTasks = pendingTasks; }
    
    public long getAssignedTasks() { return assignedTasks; }
    public void setAssignedTasks(long assignedTasks) { this.assignedTasks = assignedTasks; }
    
    public long getInProgressTasks() { return inProgressTasks; }
    public void setInProgressTasks(long inProgressTasks) { this.inProgressTasks = inProgressTasks; }
    
    public long getCompletedTasks() { return completedTasks; }
    public void setCompletedTasks(long completedTasks) { this.completedTasks = completedTasks; }
    
    public long getCancelledTasks() { return cancelledTasks; }
    public void setCancelledTasks(long cancelledTasks) { this.cancelledTasks = cancelledTasks; }

    public long getTotalActiveAssignments() { return totalActiveAssignments; }
    public void setTotalActiveAssignments(long totalActiveAssignments) { this.totalActiveAssignments = totalActiveAssignments; }

    public long getTotalStaffWithWorkload() { return totalStaffWithWorkload; }
    public void setTotalStaffWithWorkload(long totalStaffWithWorkload) { this.totalStaffWithWorkload = totalStaffWithWorkload; }
    
    public long getLowWorkloadStaff() { return lowWorkloadStaff; }
    public void setLowWorkloadStaff(long lowWorkloadStaff) { this.lowWorkloadStaff = lowWorkloadStaff; }
    
    public long getNormalWorkloadStaff() { return normalWorkloadStaff; }
    public void setNormalWorkloadStaff(long normalWorkloadStaff) { this.normalWorkloadStaff = normalWorkloadStaff; }
    
    public long getHighWorkloadStaff() { return highWorkloadStaff; }
    public void setHighWorkloadStaff(long highWorkloadStaff) { this.highWorkloadStaff = highWorkloadStaff; }
    
    public long getAtRiskStaff() { return atRiskStaff; }
    public void setAtRiskStaff(long atRiskStaff) { this.atRiskStaff = atRiskStaff; }
}
