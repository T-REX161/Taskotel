package com.hotel.workload.support;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A temporary cross-department support assignment: a staff member from their home department helps with one waiting
 * task of another (receiving) department until that task is completed.
 *
 * <p>This record sits beside the normal {@code Assignment} that actually gives the person the task. It never changes
 * the staff member's permanent department: the home department is only recorded here, as a snapshot.
 *
 * <p>Staff, task, departments and the approving user are stored as plain identifiers plus names and codes (no foreign
 * keys), so the history stays readable and valid after a task or staff record is later deleted.
 */
@Entity
@Table(name = "support_assignments",
        indexes = {
                // At most one ACTIVE support per task is enforced by a partial unique index (migration V6); an ended record
                // stays as history, so the same task can be supported again later.
                @Index(name = "idx_support_assignments_task_status", columnList = "task_id, status"),
                @Index(name = "idx_support_assignments_staff_status", columnList = "staff_id, status"),
                @Index(name = "idx_support_assignments_plan", columnList = "plan_id")
        })
public class SupportAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Groups the assignments that were confirmed together as one plan. */
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "staff_name", nullable = false)
    private String staffName;

    @Column(name = "home_department_code", nullable = false, length = 64)
    private String homeDepartmentCode;

    @Column(name = "receiving_department_code", nullable = false, length = 64)
    private String receivingDepartmentCode;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "task_title", nullable = false)
    private String taskTitle;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "approved_by_user_id")
    private UUID approvedByUserId;

    @Column(name = "approved_by_username", nullable = false)
    private String approvedByUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupportStatus status = SupportStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 30)
    private SupportEndReason endReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Set when a manager removes an ended record from the history list. The record itself is kept (archived, not deleted). */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "archived_by_username")
    private String archivedByUsername;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** Ends this support: records the new status, why, and when. */
    public void end(SupportStatus newStatus, SupportEndReason reason, LocalDateTime when) {
        this.status = newStatus;
        this.endReason = reason;
        this.completedAt = when;
    }

    /** Removes this (ended) record from the history list, keeping it as an audit record. */
    public void archive(String username, LocalDateTime when) {
        this.archivedAt = when;
        this.archivedByUsername = username;
    }

    public LocalDateTime getArchivedAt() { return archivedAt; }
    public String getArchivedByUsername() { return archivedByUsername; }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }
    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }
    public String getHomeDepartmentCode() { return homeDepartmentCode; }
    public void setHomeDepartmentCode(String homeDepartmentCode) { this.homeDepartmentCode = homeDepartmentCode; }
    public String getReceivingDepartmentCode() { return receivingDepartmentCode; }
    public void setReceivingDepartmentCode(String receivingDepartmentCode) { this.receivingDepartmentCode = receivingDepartmentCode; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String getTaskTitle() { return taskTitle; }
    public void setTaskTitle(String taskTitle) { this.taskTitle = taskTitle; }
    public UUID getAssignmentId() { return assignmentId; }
    public void setAssignmentId(UUID assignmentId) { this.assignmentId = assignmentId; }
    public UUID getApprovedByUserId() { return approvedByUserId; }
    public void setApprovedByUserId(UUID approvedByUserId) { this.approvedByUserId = approvedByUserId; }
    public String getApprovedByUsername() { return approvedByUsername; }
    public void setApprovedByUsername(String approvedByUsername) { this.approvedByUsername = approvedByUsername; }
    public SupportStatus getStatus() { return status; }
    public SupportEndReason getEndReason() { return endReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public Long getVersion() { return version; }
}
