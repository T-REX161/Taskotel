package com.hotel.workload.workload;

import com.hotel.workload.staff.Availability;
import java.util.UUID;

/**
 * DTO representing the calculated workload summary for a single staff member.
 * Workload is computed on-the-fly from PostgreSQL data; nothing is persisted.
 */
public class WorkloadDto {

    private UUID staffId;
    private String staffName;
    private String department;
    private String departmentName;
    private Availability availability;
    private int activeTaskCount;
    private int totalEstimatedDuration;
    private WorkloadStatus workloadStatus;

    public WorkloadDto() {}

    // ── Factory / calculation ─────────────────────────────────────────────────

    /**
     * Derive a WorkloadStatus from a total estimated duration in minutes.
     *
     * LOW     →   0 – 120 min
     * NORMAL  → 121 – 240 min
     * HIGH    → 241 – 360 min
     * AT_RISK →    > 360 min
     */
    public static WorkloadStatus deriveStatus(int totalMinutes) {
        if (totalMinutes <= 120) {
            return WorkloadStatus.LOW;
        } else if (totalMinutes <= 240) {
            return WorkloadStatus.NORMAL;
        } else if (totalMinutes <= 360) {
            return WorkloadStatus.HIGH;
        } else {
            return WorkloadStatus.AT_RISK;
        }
    }

    // ── Getters and Setters ───────────────────────────────────────────────────

    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }

    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }

    public Availability getAvailability() { return availability; }
    public void setAvailability(Availability availability) { this.availability = availability; }

    public int getActiveTaskCount() { return activeTaskCount; }
    public void setActiveTaskCount(int activeTaskCount) { this.activeTaskCount = activeTaskCount; }

    public int getTotalEstimatedDuration() { return totalEstimatedDuration; }
    public void setTotalEstimatedDuration(int totalEstimatedDuration) {
        this.totalEstimatedDuration = totalEstimatedDuration;
    }

    public WorkloadStatus getWorkloadStatus() { return workloadStatus; }
    public void setWorkloadStatus(WorkloadStatus workloadStatus) {
        this.workloadStatus = workloadStatus;
    }
}
