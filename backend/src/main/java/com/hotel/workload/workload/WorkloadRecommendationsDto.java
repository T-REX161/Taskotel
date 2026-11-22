package com.hotel.workload.workload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response of GET /api/workload/recommendations: suggestions only. Nothing described here has been approved,
 * and no staff member or task has been moved.
 */
public record WorkloadRecommendationsDto(
        LocalDateTime generatedAt,
        List<DepartmentRecommendation> recommendations,
        List<UnsupportedDepartment> unsupported,
        List<String> limitations) {

    public enum Severity { CRITICAL, HIGH, MODERATE }

    /**
     * What is waiting in a department that has no assignee, compared with the staff free to take it, and how loaded the department is against
     * its staff capacity (see DepartmentCapacity). The capacity fields are 0 / null when the recommendation was made without a capacity model.
     * needReason: UNCOVERED_TASKS (more waiting tasks than free staff), HIGH_UTILIZATION (at or above the Capacity Threshold) or BOTH.
     */
    public record Pressure(
            int waitingTasks,
            int waitingMinutes,
            int urgentOrHighTasks,
            int overdueOrDueSoonTasks,
            int activeStaff,
            int availableStaff,
            int freeStaff,
            int uncoveredTasks,
            int uncoveredMinutes,
            int tasksWithoutDuration,
            int remainingMinutes,
            int eligibleStaff,
            long capacityMinutes,
            Double utilizationPercent,
            WorkloadStatus level,
            boolean atRisk,
            boolean noCapacity,
            String needReason) {}

    /** A department with more waiting work than free staff, and the staff other departments could lend it. */
    public record DepartmentRecommendation(
            String departmentCode,
            String departmentName,
            Severity severity,
            Pressure pressure,
            List<String> reasons,
            List<Suggestion> suggestions) {}

    public record Suggestion(
            UUID staffId,
            String staffName,
            String homeDepartmentCode,
            String homeDepartmentName,
            String coversTaskTitle,
            DonorImpact donorImpact,
            ReceiverImpact receiverImpact,
            List<String> reasons) {}

    /**
     * The lending department before and after this person helps elsewhere. utilizationBefore / utilizationAfter: its remaining task minutes
     * against its staff capacity (its minutes do not change, its capacity falls by this person); null without a capacity model.
     */
    public record DonorImpact(int freeStaffBefore, int freeStaffAfter, int availableStaffBefore, int availableStaffAfter, int waitingTasks,
                              Double utilizationBefore, Double utilizationAfter) {}

    /**
     * The receiving department's uncovered work before and after this person helps, and its utilization before and after (its remaining
     * minutes do not change, its capacity rises by this person). stillAtRiskAfter: still at or above the threshold with this person's
     * capacity; null without a capacity model.
     */
    public record ReceiverImpact(int uncoveredTasksBefore, int uncoveredTasksAfter, int uncoveredMinutesBefore, int uncoveredMinutesAfter,
                                 Double utilizationBefore, Double utilizationAfter, Boolean stillAtRiskAfter) {}

    /** A department under pressure for which no other department can safely lend anyone. */
    public record UnsupportedDepartment(String departmentCode, String departmentName, Severity severity, Pressure pressure, String reason) {}
}
