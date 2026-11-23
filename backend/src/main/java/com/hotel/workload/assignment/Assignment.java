package com.hotel.workload.assignment;

import com.hotel.workload.staff.Staff;
import com.hotel.workload.task.Task;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
/**
 * One staff member on one task. A task may have several assignments (several staff members), but the same staff member only once: the
 * unique key on (task_id, staff_id) is the database rule (migration V18; before it, V3 allowed one row per task).
 */
@Table(name = "assignments", uniqueConstraints = @UniqueConstraint(name = "uk_assignments_task_staff", columnNames = {"task_id", "staff_id"}))
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false)
    private Staff staff;

    @Column(nullable = false)
    private LocalDateTime assignedAt;

    /**
     * The account (Admin or Manager) that created this assignment; null when unknown (rows from before V26). A Staff member accepting a task
     * THEMSELVES is not an assignment by anyone: it must never be written here (that history belongs in its own, separate field), so the
     * department Manager still receives the completion notice when this is null. Set once, when the assignment
     * is made, and never changed by a later reassignment: it is the original assigner, kept as history.
     */
    @Column(name = "assigned_by_user_id")
    private UUID assignedByUserId;

    @PrePersist
    protected void onCreate() {
        assignedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Task getTask() { return task; }
    public void setTask(Task task) { this.task = task; }
    public Staff getStaff() { return staff; }
    public void setStaff(Staff staff) { this.staff = staff; }
    public UUID getAssignedByUserId() { return assignedByUserId; }
    public void setAssignedByUserId(UUID assignedByUserId) { this.assignedByUserId = assignedByUserId; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }
}
