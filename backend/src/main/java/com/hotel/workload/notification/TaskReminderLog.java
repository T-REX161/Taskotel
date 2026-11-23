package com.hotel.workload.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One reminder for one recipient (migration V27). The unique key (task, recipient, kind, slot, due moment) is what makes a reminder
 * impossible to claim twice, by two scheduler runs or two application instances. The row is written BEFORE the email is handed to the
 * notification worker and its status is only moved to SENT once the worker has actually attempted delivery.
 */
@Entity
@Table(name = "task_reminder_log", uniqueConstraints = @UniqueConstraint(name = "uk_task_reminder_log_key",
        columnNames = {"task_id", "recipient_user_id", "kind", "slot", "due_key"}))
public class TaskReminderLog {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReminderKind kind;

    @Column(nullable = false)
    private int slot;

    /** The task's due date and deadline time as text when this was claimed: a changed due date starts a fresh set of reminders. */
    @Column(name = "due_key", nullable = false, length = 32)
    private String dueKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReminderStatus status;

    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    protected TaskReminderLog() {}

    public TaskReminderLog(UUID taskId, UUID recipientUserId, ReminderKind kind, int slot, String dueKey, LocalDateTime claimedAt) {
        this.id = UUID.randomUUID();
        this.taskId = taskId;
        this.recipientUserId = recipientUserId;
        this.kind = kind;
        this.slot = slot;
        this.dueKey = dueKey;
        this.status = ReminderStatus.CLAIMED;
        this.claimedAt = claimedAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public ReminderKind getKind() { return kind; }
    public int getSlot() { return slot; }
    public String getDueKey() { return dueKey; }
    public ReminderStatus getStatus() { return status; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }

    public void finish(ReminderStatus outcome, LocalDateTime at) {
        this.status = outcome;
        this.finishedAt = at;
    }
}
