package com.hotel.workload.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One append-only audit record: an Administrator changed an account's system role (V25).
 *
 * <p>Like the Manager department and view-access audits, people are plain identifiers with name snapshots and no foreign keys, so the
 * record stays readable after an account is renamed or removed. Nothing ever updates or deletes a row. It holds who acted, who was
 * changed, the previous and the new role, when, and how many Manager department grants ended with the change.
 */
@Entity
@Table(name = "user_role_changes", indexes = @Index(name = "ix_user_role_changes_target", columnList = "target_user_id, changed_at"))
public class UserRoleChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;

    @Column(name = "target_username", nullable = false, updatable = false)
    private String targetUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_role", nullable = false, updatable = false, length = 16)
    private Role previousRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_role", nullable = false, updatable = false, length = 16)
    private Role newRole;

    @Column(name = "revoked_grants", nullable = false, updatable = false)
    private int revokedGrants;

    @Column(name = "changed_by_user_id", nullable = false, updatable = false)
    private UUID changedByUserId;

    @Column(name = "changed_by_username", nullable = false, updatable = false)
    private String changedByUsername;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    void onCreate() {
        if (changedAt == null) changedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getTargetUserId() { return targetUserId; }
    public void setTargetUserId(UUID targetUserId) { this.targetUserId = targetUserId; }
    public String getTargetUsername() { return targetUsername; }
    public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }
    public Role getPreviousRole() { return previousRole; }
    public void setPreviousRole(Role previousRole) { this.previousRole = previousRole; }
    public Role getNewRole() { return newRole; }
    public void setNewRole(Role newRole) { this.newRole = newRole; }
    public int getRevokedGrants() { return revokedGrants; }
    public void setRevokedGrants(int revokedGrants) { this.revokedGrants = revokedGrants; }
    public UUID getChangedByUserId() { return changedByUserId; }
    public void setChangedByUserId(UUID changedByUserId) { this.changedByUserId = changedByUserId; }
    public String getChangedByUsername() { return changedByUsername; }
    public void setChangedByUsername(String changedByUsername) { this.changedByUsername = changedByUsername; }
    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }
}
