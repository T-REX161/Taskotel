package com.hotel.workload.staff;

import com.hotel.workload.security.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Access for a Manager to one department other than their own, granted by an Administrator (V22; full management since the access-model
 * change, V23).
 *
 * <p>A grant makes that department one of the Manager's managed departments, with the same operational access as their primary one (see
 * {@code DepartmentScope.managedDepartments}). Only an Administrator can grant or revoke it. Grants are
 * read from the database on every request ({@code CurrentUserScopeResolver}), so revoking one takes effect on the next request.
 *
 * <p>The rows go with the account (ON DELETE CASCADE). A department that still has a grant is not removed: the service refuses (409)
 * until an Administrator revokes the grants, so a grant is never deleted silently. The cascade on the department side stays as a database
 * backstop that the service never reaches.
 */
@Entity
@Table(name = "manager_view_access",
        uniqueConstraints = @UniqueConstraint(name = "uk_manager_view_access", columnNames = {"manager_user_id", "department_id"}))
public class ManagerViewGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manager_user_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_manager_view_access_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User manager;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "department_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_manager_view_access_department"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Department department;

    @Column(name = "granted_by_user_id", nullable = false, updatable = false)
    private UUID grantedByUserId;

    @Column(name = "granted_by_username", nullable = false, updatable = false)
    private String grantedByUsername;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    @PrePersist
    protected void onCreate() {
        if (grantedAt == null) {
            grantedAt = LocalDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public User getManager() { return manager; }
    public void setManager(User manager) { this.manager = manager; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
    public UUID getGrantedByUserId() { return grantedByUserId; }
    public void setGrantedByUserId(UUID grantedByUserId) { this.grantedByUserId = grantedByUserId; }
    public String getGrantedByUsername() { return grantedByUsername; }
    public void setGrantedByUsername(String grantedByUsername) { this.grantedByUsername = grantedByUsername; }
    public LocalDateTime getGrantedAt() { return grantedAt; }
}
