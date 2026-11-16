package com.hotel.workload.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The last workload level seen for one Staff member and one audience, so that an alert is sent only when the level goes UP into HIGH or
 * AT_RISK and never repeats while it stays there. A Staff member has two independent rows (STAFF: their global workload; MANAGER: only the
 * work of their home department), because the two levels can differ. The unique constraint on (staff_id, audience) is what makes first-time
 * creation safe when two workers race (see {@link WorkloadAlertService}). The staff member is a plain identifier; the database removes the
 * rows together with the Staff record (foreign key with cascade in migration V24).
 */
@Entity
@Table(name = "workload_alert_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_workload_alert_state_staff_audience", columnNames = {"staff_id", "audience"}))
public class WorkloadAlertState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "audience", nullable = false, length = 8)
    private String audience;

    @Column(name = "level", nullable = false, length = 16)
    private String level;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    protected WorkloadAlertState() {}

    public WorkloadAlertState(UUID staffId, String audience, String level) {
        this.staffId = staffId;
        this.audience = audience;
        this.level = level;
        this.changedAt = LocalDateTime.now();
    }

    public void changeLevel(String newLevel) {
        this.level = newLevel;
        this.changedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getStaffId() { return staffId; }
    public String getAudience() { return audience; }
    public String getLevel() { return level; }
    public LocalDateTime getChangedAt() { return changedAt; }
}
