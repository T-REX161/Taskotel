package com.hotel.workload.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskReminderLogRepository extends JpaRepository<TaskReminderLog, UUID> {

    /** How many reminders of this kind this person has already had (or may have had) for this task and due moment: the overdue cap counts these. */
    @Query("SELECT COUNT(l) FROM TaskReminderLog l WHERE l.taskId = :taskId AND l.recipientUserId = :userId AND l.kind = :kind "
            + "AND l.dueKey = :dueKey AND l.status IN :statuses")
    long countFor(@Param("taskId") UUID taskId, @Param("userId") UUID userId, @Param("kind") ReminderKind kind,
                  @Param("dueKey") String dueKey, @Param("statuses") Collection<ReminderStatus> statuses);

    List<TaskReminderLog> findByTaskId(UUID taskId);

    /**
     * Rows still CLAIMED long after they were reserved: the worker never reported back (a crash, a stop, a dropped job, or a failed
     * bookkeeping write after the email was accepted). Whether an email went out is UNKNOWN, so they are marked UNKNOWN (which still counts
     * toward the overdue cap), never FAILED. They are never retried.
     */
    @Modifying
    @Query("UPDATE TaskReminderLog l SET l.status = com.hotel.workload.notification.ReminderStatus.UNKNOWN, l.finishedAt = :now "
            + "WHERE l.status = com.hotel.workload.notification.ReminderStatus.CLAIMED AND l.claimedAt < :cutoff")
    int expireStaleClaims(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);

    /**
     * Records what became of ONE claimed reminder. Only a row that is still CLAIMED, or was swept to UNKNOWN while the worker was slow, is
     * updated, so a final outcome is never overwritten. Returns 0 when the row is gone (its task was deleted) or already final.
     */
    @Modifying
    @Query("UPDATE TaskReminderLog l SET l.status = :status, l.finishedAt = :now WHERE l.id = :id "
            + "AND l.status IN (com.hotel.workload.notification.ReminderStatus.CLAIMED, com.hotel.workload.notification.ReminderStatus.UNKNOWN)")
    int finishClaim(@Param("id") UUID id, @Param("status") ReminderStatus status, @Param("now") LocalDateTime now);

    /**
     * Gives back claims that were never handed to a worker. Deletes ONLY the listed rows (the caller's own run) and only while they are
     * still CLAIMED, so no other run's claim, and no row that already has an outcome, can be removed.
     */
    @Modifying
    @Query("DELETE FROM TaskReminderLog l WHERE l.id IN :ids AND l.status = com.hotel.workload.notification.ReminderStatus.CLAIMED")
    int releaseClaims(@Param("ids") Collection<UUID> ids);
}
