package com.hotel.workload.support;

import com.hotel.workload.staff.Availability;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Severity;
import com.hotel.workload.workload.WorkloadStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Response shapes of the /api/support endpoints. */
public final class SupportDtos {
    private SupportDtos() {}

    /**
     * Workload figures for one department. Minutes are the existing workload measure (estimated duration of active
     * tasks, 0 when missing). A supported task counts under the receiving department, never twice.
     */
    /** The one System Settings fact a Manager may read: whether NEW cross-department support can be planned. Nothing else from the settings. */
    public record CrossDepartmentStatus(boolean crossDepartmentAssistanceEnabled) {}

    public record Metrics(int waitingTasks, int waitingMinutes, int assignedMinutes, int availableStaff, int freeStaff,
                          int uncoveredTasks, int uncoveredMinutes) {}

    /** The receiving department with what is waiting in it. */
    public record DepartmentSummary(String code, String name, Severity severity, int urgentOrHighTasks,
                                    int overdueOrDueSoonTasks, int activeStaff, Metrics metrics) {}

    public record WaitingTask(UUID id, String title, String category, Priority priority, Integer estimatedDuration,
                              LocalDate dueDate, LocalTime deadlineTime) {}

    /** The workload figures are null in a Manager's response (they are the donor department's data). */
    public record DonorStaff(UUID staffId, String name, Availability availability, Integer activeTaskCount,
                             Integer totalEstimatedDuration, WorkloadStatus workloadStatus) {}

    /**
     * A department that has staff who could help, and how many it may lend while keeping its reserve. {@code overrideLendable}
     * is how many it could lend if a manager acknowledges the staffing risk: its own waiting work stays covered, but the
     * one free person it normally keeps in reserve may go (never fewer than {@code maxLendable}).
     */
    public record DonorDepartment(String code, String name, Integer freeStaff, Integer availableStaff, Integer waitingTasks,
                                  Integer maxLendable, Integer overrideLendable, Boolean canLend, String note, List<DonorStaff> staff) {}

    public record Options(DepartmentSummary receiving, List<WaitingTask> tasks, int maxHelpers,
                          List<DonorDepartment> donors, List<String> limitations) {}

    /** One thing wrong with a plan. {@code code} is stable for clients; {@code message} is for people. */
    public record Problem(String code, String message, UUID staffId, UUID taskId) {}

    public record PreviewItem(UUID staffId, String staffName, String homeDepartmentCode, String homeDepartmentName,
                              UUID taskId, String taskTitle, Priority priority, int taskMinutes) {}

    /** Before and after for one department. {@code role} is RECEIVING or DONOR. */
    public record DepartmentImpact(String role, String code, String name, Metrics before, Metrics after,
                                   int reserveRequired, int lending) {}

    /**
     * {@code warnings} are things that do not block the plan but must stay visible (code RESERVE_OVERRIDE: a donor will be
     * left below its reserve, acknowledged by the manager). {@code canOverrideReserve} is true when the plan is blocked
     * only by the donor reserve and acknowledging the risk would allow it.
     */
    public record Preview(boolean valid, List<Problem> problems, List<Problem> warnings, boolean canOverrideReserve,
                          List<PreviewItem> items, DepartmentImpact receiving,
                          List<DepartmentImpact> donors, String fingerprint, List<String> limitations) {}

    public record SupportAssignmentDto(UUID id, UUID planId, UUID staffId, String staffName, String homeDepartmentCode,
                                       String receivingDepartmentCode, UUID taskId, String taskTitle, UUID assignmentId,
                                       String approvedBy, SupportStatus status, SupportEndReason endReason,
                                       LocalDateTime createdAt, LocalDateTime completedAt, TaskStatus taskStatus,
                                       Boolean reserveOverride, LocalDateTime archivedAt) {
        public static SupportAssignmentDto from(SupportAssignment s) {
            return from(s, null);
        }

        /**
         * A Manager's view of a record where their department RECEIVED the support: the details needed to understand it, but never the approver
         * (`approvedBy`) or the one-time confirmation flag.
         */
        public static SupportAssignmentDto forReceivingManager(SupportAssignment s, TaskStatus taskStatus) {
            return new SupportAssignmentDto(s.getId(), s.getPlanId(), s.getStaffId(), s.getStaffName(), s.getHomeDepartmentCode(),
                    s.getReceivingDepartmentCode(), s.getTaskId(), s.getTaskTitle(), s.getAssignmentId(),
                    null, s.getStatus(), s.getEndReason(), s.getCreatedAt(), s.getCompletedAt(), taskStatus, null, s.getArchivedAt());
        }

        /**
         * A Manager's view of a record where their department only LENT the person: the person's name and the fact that they are on a temporary
         * loan (status and dates). No receiving department, no task, no plan, no approver and no end reason.
         */
        public static SupportAssignmentDto forDonorManager(SupportAssignment s) {
            return new SupportAssignmentDto(s.getId(), null, s.getStaffId(), s.getStaffName(), s.getHomeDepartmentCode(),
                    null, null, null, null, null, s.getStatus(), null, s.getCreatedAt(), s.getCompletedAt(), null, null, s.getArchivedAt());
        }

        /** {@code taskStatus} is the supported task's current status (null when unknown or the task is gone). */
        public static SupportAssignmentDto from(SupportAssignment s, TaskStatus taskStatus) {
            return from(s, taskStatus, null);
        }

        /**
         * {@code reserveOverride} is reported only in the response to the confirmation itself (true when the donor department
         * was left below its reserve with the manager's acknowledgement). It is not stored, so later reads show null.
         */
        public static SupportAssignmentDto from(SupportAssignment s, TaskStatus taskStatus, Boolean reserveOverride) {
            return new SupportAssignmentDto(s.getId(), s.getPlanId(), s.getStaffId(), s.getStaffName(), s.getHomeDepartmentCode(),
                    s.getReceivingDepartmentCode(), s.getTaskId(), s.getTaskTitle(), s.getAssignmentId(),
                    s.getApprovedByUsername(), s.getStatus(), s.getEndReason(), s.getCreatedAt(), s.getCompletedAt(), taskStatus, reserveOverride,
                    s.getArchivedAt());
        }
    }
}
