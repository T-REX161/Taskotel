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

    /** What is waiting in a department that has no assignee, compared with the staff free to take it. */
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
            int tasksWithoutDuration) {}

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

    /** The lending department before and after this person helps elsewhere. */
    public record DonorImpact(int freeStaffBefore, int freeStaffAfter, int availableStaffBefore, int availableStaffAfter, int waitingTasks) {}

    /** The receiving department's uncovered work before and after this person helps. */
    public record ReceiverImpact(int uncoveredTasksBefore, int uncoveredTasksAfter, int uncoveredMinutesBefore, int uncoveredMinutesAfter) {}

    /** A department under pressure for which no other department can safely lend anyone. */
    public record UnsupportedDepartment(String departmentCode, String departmentName, Severity severity, Pressure pressure, String reason) {}
}
