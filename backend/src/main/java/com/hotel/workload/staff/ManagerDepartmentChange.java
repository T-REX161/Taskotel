package com.hotel.workload.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One append-only audit record: a Manager's department was changed by an Admin (Decision 17.6).
 *
 * <p>Like {@code support_assignments}, the people and departments are plain identifiers with name snapshots and no foreign keys, so
 * the record stays correct and readable after a department is renamed or a user is removed. Nothing ever updates or deletes a row.
 */
@Entity
@Table(name = "manager_department_changes", indexes = @Index(name = "ix_mgr_dept_changes_manager", columnList = "manager_user_id, changed_at"))
public class ManagerDepartmentChange {

    /** The dedicated Admin operation. */
    public static final String SOURCE_ASSIGNMENT = "MANAGER_DEPARTMENT_ASSIGNMENT";
    /** An Admin ordinary staff edit that moved a Manager staff record to another department. */
    public static final String SOURCE_STAFF_EDIT = "STAFF_EDIT";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "manager_user_id", nullable = false, updatable = false)
    private UUID managerUserId;

    @Column(name = "manager_username", nullable = false, updatable = false)
    private String managerUsername;

    @Column(name = "staff_id", nullable = false, updatable = false)
    private UUID staffId;

    @Column(name = "staff_name", nullable = false, updatable = false)
    private String staffName;

    @Column(name = "previous_department_id", updatable = false)
    private UUID previousDepartmentId;

    @Column(name = "previous_department_code", nullable = false, updatable = false, length = 64)
    private String previousDepartmentCode;

    @Column(name = "previous_department_name", nullable = false, updatable = false, length = 100)
    private String previousDepartmentName;

    @Column(name = "new_department_id", updatable = false)
    private UUID newDepartmentId;

    @Column(name = "new_department_code", nullable = false, updatable = false, length = 64)
    private String newDepartmentCode;

    @Column(name = "new_department_name", nullable = false, updatable = false, length = 100)
    private String newDepartmentName;

    @Column(name = "changed_by_user_id", nullable = false, updatable = false)
    private UUID changedByUserId;

    @Column(name = "changed_by_username", nullable = false, updatable = false)
    private String changedByUsername;

    @Column(name = "change_source", nullable = false, updatable = false, length = 40)
    private String changeSource;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getManagerUserId() { return managerUserId; }
    public void setManagerUserId(UUID managerUserId) { this.managerUserId = managerUserId; }
    public String getManagerUsername() { return managerUsername; }
    public void setManagerUsername(String managerUsername) { this.managerUsername = managerUsername; }
    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }
    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }
    public UUID getPreviousDepartmentId() { return previousDepartmentId; }
    public void setPreviousDepartmentId(UUID previousDepartmentId) { this.previousDepartmentId = previousDepartmentId; }
    public String getPreviousDepartmentCode() { return previousDepartmentCode; }
    public void setPreviousDepartmentCode(String previousDepartmentCode) { this.previousDepartmentCode = previousDepartmentCode; }
    public String getPreviousDepartmentName() { return previousDepartmentName; }
    public void setPreviousDepartmentName(String previousDepartmentName) { this.previousDepartmentName = previousDepartmentName; }
    public UUID getNewDepartmentId() { return newDepartmentId; }
    public void setNewDepartmentId(UUID newDepartmentId) { this.newDepartmentId = newDepartmentId; }
    public String getNewDepartmentCode() { return newDepartmentCode; }
    public void setNewDepartmentCode(String newDepartmentCode) { this.newDepartmentCode = newDepartmentCode; }
    public String getNewDepartmentName() { return newDepartmentName; }
    public void setNewDepartmentName(String newDepartmentName) { this.newDepartmentName = newDepartmentName; }
    public UUID getChangedByUserId() { return changedByUserId; }
    public void setChangedByUserId(UUID changedByUserId) { this.changedByUserId = changedByUserId; }
    public String getChangedByUsername() { return changedByUsername; }
    public void setChangedByUsername(String changedByUsername) { this.changedByUsername = changedByUsername; }
    public String getChangeSource() { return changeSource; }
    public void setChangeSource(String changeSource) { this.changeSource = changeSource; }
    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }
}
