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
 * One append-only audit record: an Administrator granted or revoked a Manager's view-only access to a department (V22).
 *
 * <p>Like {@link ManagerDepartmentChange}, people and departments are plain identifiers with name snapshots and no foreign keys, so the
 * record stays readable after a rename or a removal. Nothing ever updates or deletes a row.
 */
@Entity
@Table(name = "manager_view_access_changes", indexes = @Index(name = "ix_mgr_view_changes_manager", columnList = "manager_user_id, changed_at"))
public class ManagerViewGrantChange {

    public static final String ACTION_GRANTED = "GRANTED";
    public static final String ACTION_REVOKED = "REVOKED";

    /** The Admin view-access operation. */
    public static final String SOURCE_ADMIN = "ADMIN_VIEW_ACCESS";
    /** A grant made redundant because the Manager's primary department became that department. */
    public static final String SOURCE_PRIMARY_CHANGE = "PRIMARY_DEPARTMENT_CHANGE";
    /**
     * A grant that existed under the earlier view-only model and became full management with the access-model change. Written only by the
     * hand-run migration V23 (never by the application), with the change source {@link #SOURCE_ACCESS_MODEL_V23}.
     */
    public static final String ACTION_UPGRADED = "UPGRADED";
    public static final String SOURCE_ACCESS_MODEL_V23 = "ACCESS_MODEL_V23";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "manager_user_id", nullable = false, updatable = false)
    private UUID managerUserId;

    @Column(name = "manager_username", nullable = false, updatable = false)
    private String managerUsername;

    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;

    @Column(name = "department_code", nullable = false, updatable = false, length = 64)
    private String departmentCode;

    @Column(name = "department_name", nullable = false, updatable = false, length = 100)
    private String departmentName;

    @Column(name = "action", nullable = false, updatable = false, length = 16)
    private String action;

    @Column(name = "change_source", nullable = false, updatable = false, length = 40)
    private String changeSource;

    @Column(name = "changed_by_user_id", nullable = false, updatable = false)
    private UUID changedByUserId;

    @Column(name = "changed_by_username", nullable = false, updatable = false)
    private String changedByUsername;

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
    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getChangeSource() { return changeSource; }
    public void setChangeSource(String changeSource) { this.changeSource = changeSource; }
    public UUID getChangedByUserId() { return changedByUserId; }
    public void setChangedByUserId(UUID changedByUserId) { this.changedByUserId = changedByUserId; }
    public String getChangedByUsername() { return changedByUsername; }
    public void setChangedByUsername(String changedByUsername) { this.changedByUsername = changedByUsername; }
    public LocalDateTime getChangedAt() { return changedAt; }
}
