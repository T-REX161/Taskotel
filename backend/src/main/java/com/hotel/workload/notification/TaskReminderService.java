package com.hotel.workload.notification;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.NotificationEmailTemplates.ReminderLine;
import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.notification.NotificationService.Outcome;
import com.hotel.workload.notification.TaskReminderRules.Fire;
import com.hotel.workload.staff.Department;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task reminder emails (category TASK_REMINDER). One {@link #run} is one scheduler tick; see {@link TaskReminderRules} for WHEN a reminder
 * falls (Asia/Kolkata) and {@code docs/TASK_REMINDERS.md} for the full rules.
 *
 * <p><b>Who.</b> Recipients are read from the database at the time: the people currently assigned to the task (an active Staff record with
 * an account), and the Managers currently responsible for its department (the existing primary-department and active-grant rules, valid
 * accounts only). Due-soon reminders go to the assignees; overdue reminders go to the assignees and the Managers; a task nobody holds
 * goes to the Managers only ("needs assigning"). Each recipient receives one email per run listing their tasks. The recipient's own
 * TASK_REMINDER preference, account eligibility and address de-duplication are applied by {@link NotificationService}.
 *
 * <p><b>The overdue cap.</b> At most {@value TaskReminderRules#MAX_OVERDUE_EMAILS} overdue emails per task, recipient and due moment, the
 * first included. Someone assigned late starts counting from their first overdue email, and only ever receives the CURRENT daily slot,
 * never the ones that were missed.
 *
 * <p><b>Delivery guarantee, stated plainly.</b>
 * <ol>
 *   <li>A reminder is first CLAIMED: a log row is inserted (unique per task, recipient, kind, slot and due moment) in its own short
 *       transaction. Two runs, two threads or two application instances cannot both claim it, so a reminder is never sent twice for the same
 *       slot. This is AT MOST ONCE.</li>
 *   <li>The claim is NOT a delivery record. The job is handed to the notification worker, and the row only changes when the worker reports
 *       what became of it: SENT (the provider accepted the request, not proof an inbox received it); FAILED (the provider CONFIRMED a
 *       refusal: nothing was sent); UNKNOWN (a broken or timed-out connection: the request may have been accepted); SKIPPED (the recipient
 *       was not eligible or opted out, or the task finished or changed); EXPIRED (the reminder's window had closed before the worker
 *       reached it, so it was NOT sent late). Every outcome is written per row, so one failed write cannot affect another row.</li>
 *   <li>If the worker REJECTS the job (queue full or shutting down), or claiming stops part-way, the claims of THIS run are deleted again
 *       (only those, and only while still CLAIMED), so a later tick may try; a reminder is only retried while its moment is still inside the
 *       grace window ({@code taskotel.reminders.grace-minutes}, default 30), after which it is dropped, not sent late.</li>
 *   <li>Nothing is retried after an outcome is recorded. A row still CLAIMED an hour later (the worker never reported back: a crash, a
 *       stop, or a failed bookkeeping write AFTER the email was accepted) becomes UNKNOWN, never FAILED: it may have been sent. CLAIMED, SENT
 *       and UNKNOWN count toward the overdue cap, so a lost log write can never lead to a fourth overdue email.</li>
 *   <li>The reminder's moment is checked against the CURRENT time again on the worker, immediately before sending, not against the time
 *       the scheduler claimed it: a backed-up queue cannot deliver a stale reminder.</li>
 * </ol>
 * Delivery is therefore best effort and at most once. It is NOT guaranteed. No email is ever sent while a database transaction or lock is held.
 */
@Service
public class TaskReminderService {

    private static final Logger log = LoggerFactory.getLogger(TaskReminderService.class);
    private static final Set<TaskStatus> OPEN = EnumSet.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);
    private static final Duration STALE_CLAIM = Duration.ofHours(1);
    /**
     * The statuses that count as "an overdue email was (or may have been) sent": in flight, accepted by the provider, or of unknown outcome. A
     * confirmed refusal, a skip and an expiry do not: nothing went out.
     */
    private static final List<ReminderStatus> CAP_COUNTED = List.of(ReminderStatus.CLAIMED, ReminderStatus.SENT, ReminderStatus.UNKNOWN);

    /** One reminder for one person that is due now. */
    record Pending(UUID taskId, UUID userId, ReminderKind kind, int slot, String dueKey, boolean manager, boolean unassignedOnly) {}

    /** A pending reminder that was claimed, with its log row. */
    record Claimed(Pending pending, UUID logId) {}

    /** What one run did, for tests and the log. */
    public record RunResult(int due, int claimed, int alreadyClaimed, int capped, int queued, int rejected, boolean claimingStoppedEarly) {
        static RunResult nothing() { return new RunResult(0, 0, 0, 0, 0, 0, false); }
    }

    enum ClaimState { CLAIMED, ALREADY, CAPPED }

    record ClaimAttempt(ClaimState state, UUID logId) {}

    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final TaskReminderLogRepository logRepository;
    private final TaskEmailNotifier managers;
    private final NotificationService notifications;
    private final PlatformTransactionManager transactionManager;
    private final Duration grace;

    public TaskReminderService(TaskRepository taskRepository, AssignmentRepository assignmentRepository, TaskReminderLogRepository logRepository,
                               TaskEmailNotifier managers, NotificationService notifications, PlatformTransactionManager transactionManager,
                               @Value("${taskotel.reminders.grace-minutes:30}") long graceMinutes) {
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.logRepository = logRepository;
        this.managers = managers;
        this.notifications = notifications;
        this.transactionManager = transactionManager;
        this.grace = Duration.ofMinutes(Math.max(1, graceMinutes));
    }

    private TransactionTemplate newTransaction(boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    /**
     * The current moment, read on the worker immediately before a reminder is sent (never the time the scheduler claimed it). A method of its
     * own so a test can move the clock to simulate a backed-up queue.
     */
    protected ZonedDateTime deliveryNow() {
        return ZonedDateTime.now(TaskReminderRules.ZONE);
    }

    /** One scheduler tick at {@code now}. Never throws for a delivery problem; a database failure is logged and ends the tick. */
    public RunResult run(ZonedDateTime now) {
        ZonedDateTime at = now.withZoneSameInstant(TaskReminderRules.ZONE);
        LocalDateTime stamp = at.toLocalDateTime();
        // Everything claimed by THIS run, kept outside the try so no failure below can strand it
        List<Claimed> claimed = new ArrayList<>();
        int alreadyClaimed = 0;
        int capped = 0;
        int due = 0;
        boolean stoppedEarly = false;
        try {
            if (!notifications.isEmailConfigured()) {
                log.warn("Email is not configured: no task reminders were claimed or sent");
                return RunResult.nothing();
            }
            try {
                newTransaction(false).executeWithoutResult(s -> logRepository.expireStaleClaims(stamp.minus(STALE_CLAIM), stamp));
            } catch (RuntimeException e) {
                // Housekeeping only: a failure here must not stop this run's reminders
                log.error("Marking stale task reminder claims failed: {}", e.getClass().getSimpleName());
            }

            List<Pending> pendings = newTransaction(true).execute(s -> gather(at));
            if (pendings == null || pendings.isEmpty()) return RunResult.nothing();
            due = pendings.size();

            for (Pending pending : pendings) {
                ClaimAttempt attempt;
                try {
                    attempt = claim(pending, stamp);
                } catch (RuntimeException e) {
                    // A database failure part-way: stop claiming, but the reminders already claimed are still handled below. The unclaimed ones
                    // stay available to the next tick (while inside their window). A claim that failed AT commit may or may not have been
                    // written; if it was, it is a CLAIMED row that the stale sweep turns into UNKNOWN (counted by the cap, never retried).
                    log.error("Claiming task reminders stopped early after {} claim(s): {}", claimed.size(), e.getClass().getSimpleName());
                    stoppedEarly = true;
                    break;
                }
                switch (attempt.state()) {
                    case CLAIMED -> claimed.add(new Claimed(pending, attempt.logId()));
                    case ALREADY -> alreadyClaimed++;
                    case CAPPED -> capped++;
                }
            }
            return finishRun(claimed, due, alreadyClaimed, capped, stoppedEarly);
        } catch (RuntimeException e) {
            log.error("Task reminder run failed: {}", e.getClass().getSimpleName());
            // Whatever this run claimed before the failure is queued or given back, never silently left behind
            if (!claimed.isEmpty()) {
                try {
                    return finishRun(claimed, due, alreadyClaimed, capped, true);
                } catch (RuntimeException again) {
                    log.error("Handling this run's claims failed: {}", again.getClass().getSimpleName());
                }
            }
            return RunResult.nothing();
        }
    }

    /** Hands THIS run's claims to the worker, or gives exactly those claims back when the worker did not take them. */
    private RunResult finishRun(List<Claimed> claimed, int due, int alreadyClaimed, int capped, boolean stoppedEarly) {
        if (claimed.isEmpty()) return new RunResult(due, 0, alreadyClaimed, capped, 0, 0, stoppedEarly);
        boolean accepted;
        try {
            accepted = enqueue(claimed);
        } catch (RuntimeException e) {
            log.error("Queueing task reminders failed: {}", e.getClass().getSimpleName());
            accepted = false;
        }
        if (!accepted) release(claimed);
        return new RunResult(due, claimed.size(), alreadyClaimed, capped, accepted ? claimed.size() : 0, accepted ? 0 : claimed.size(), stoppedEarly);
    }

    // ── who is due ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Inside a read-only transaction: the reminders that are due at {@code at}, for the people who are recipients right now. */
    private List<Pending> gather(ZonedDateTime at) {
        List<Pending> pendings = new ArrayList<>();
        // Anything due tomorrow or earlier can have a reminder due now (24 hours ahead at the most)
        for (Task task : taskRepository.findOpenDueOnOrBefore(at.toLocalDate().plusDays(1), OPEN)) {
            List<Fire> fires = TaskReminderRules.dueAt(task.getDueDate(), task.getDeadlineTime(), at, grace);
            if (fires.isEmpty()) continue;
            String dueKey = TaskReminderRules.dueKey(task.getDueDate(), task.getDeadlineTime());
            Set<UUID> assignees = assigneeIds(task.getId());
            Set<UUID> managerIds = null;
            for (Fire fire : fires) {
                Set<UUID> recipients = new LinkedHashSet<>();
                boolean unassigned = assignees.isEmpty();
                if (!unassigned) {
                    // Someone assigned after this reminder's moment missed it: they are sent the next one, never an old one
                    ZonedDateTime moment = TaskReminderRules.momentOf(task.getDueDate(), task.getDeadlineTime(), fire);
                    for (UUID id : assigneeIdsAssignedBy(task.getId(), moment)) {
                        pendings.add(new Pending(task.getId(), id, fire.kind(), fire.slot(), dueKey, false, false));
                    }
                }
                // Managers: for a task nobody holds (any reminder), and for an overdue task that someone holds
                if (unassigned || fire.kind() == ReminderKind.OVERDUE) {
                    if (managerIds == null) managerIds = managerIdsOf(task);
                    recipients.addAll(managerIds);
                    for (UUID id : recipients) pendings.add(new Pending(task.getId(), id, fire.kind(), fire.slot(), dueKey, true, unassigned));
                }
            }
        }
        return pendings;
    }

    private Set<UUID> assigneeIds(UUID taskId) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Assignment assignment : assignmentRepository.findAllByTaskId(taskId)) {
            if (assignment.getStaff() != null && assignment.getStaff().isActive() && assignment.getStaff().getUser() != null) {
                ids.add(assignment.getStaff().getUser().getId());
            }
        }
        return ids;
    }

    /**
     * The current assignees who were already assigned at {@code moment}. {@code assignedAt} is written with the server's clock
     * ({@code LocalDateTime.now()}), so it is read back in the server's zone, then compared as an instant.
     */
    private Set<UUID> assigneeIdsAssignedBy(UUID taskId, ZonedDateTime moment) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Assignment assignment : assignmentRepository.findAllByTaskId(taskId)) {
            if (assignment.getStaff() == null || !assignment.getStaff().isActive() || assignment.getStaff().getUser() == null) continue;
            LocalDateTime assignedAt = assignment.getAssignedAt();
            if (assignedAt != null && assignedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().isAfter(moment.toInstant())) continue;
            ids.add(assignment.getStaff().getUser().getId());
        }
        return ids;
    }

    private Set<UUID> managerIdsOf(Task task) {
        Department department = task.getDepartment();
        return department == null ? Set.of() : managers.managersOf(department);
    }

    // ── claiming ─────────────────────────────────────────────────────────────────────────────────────────────────

    // (package-private, with the records above, so a test can make ONE claim fail and watch the loop cope)
    /** Reserves one reminder in its own short transaction. The unique key decides a race: the loser is told ALREADY and sends nothing. */
    ClaimAttempt claim(Pending pending, LocalDateTime stamp) {
        try {
            return newTransaction(false).execute(s -> {
                if (pending.kind() == ReminderKind.OVERDUE) {
                    long sent = logRepository.countFor(pending.taskId(), pending.userId(), ReminderKind.OVERDUE, pending.dueKey(),
                            CAP_COUNTED);
                    if (sent >= TaskReminderRules.MAX_OVERDUE_EMAILS) return new ClaimAttempt(ClaimState.CAPPED, null);
                }
                TaskReminderLog row = new TaskReminderLog(pending.taskId(), pending.userId(), pending.kind(), pending.slot(), pending.dueKey(), stamp);
                logRepository.saveAndFlush(row);
                return new ClaimAttempt(ClaimState.CLAIMED, row.getId());
            });
        } catch (DataIntegrityViolationException e) {
            return new ClaimAttempt(ClaimState.ALREADY, null);
        }
    }

    /**
     * The worker did not take the job (or claiming stopped part-way): give THIS run's claims back so a later tick, still inside the grace
     * window, can try. Only the ids this run claimed, and only while they are still CLAIMED, are deleted; another run's claim cannot be touched.
     * If the delete itself fails, the rows stay CLAIMED (nothing was sent): the stale sweep turns them into UNKNOWN, which is conservative.
     */
    private void release(List<Claimed> claimed) {
        try {
            List<UUID> ids = claimed.stream().map(Claimed::logId).toList();
            int removed = newTransaction(false).execute(s -> logRepository.releaseClaims(ids));
            log.warn("{} task reminder(s) were not queued; {} claim(s) were released", ids.size(), removed);
        } catch (RuntimeException e) {
            log.error("Releasing task reminder claims failed: {}", e.getClass().getSimpleName());
        }
    }

    // ── delivery ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Hands the claimed reminders to the notification worker. Returns false when the worker did not accept them. */
    private boolean enqueue(List<Claimed> claimed) {
        Set<UUID> kept = ConcurrentHashMap.newKeySet();
        Set<UUID> expired = ConcurrentHashMap.newKeySet();
        Map<UUID, List<ReminderLine>> lines = new ConcurrentHashMap<>();
        return notifications.enqueueTracked(NotificationCategory.TASK_REMINDER,
                () -> noticesFor(claimed, kept, expired, lines),
                outcomes -> finish(claimed, kept, expired, outcomes),
                userIds -> mergedContent(lines, userIds));
    }

    /**
     * On the worker, immediately before sending, inside a read-only transaction: re-checks every claimed reminder against the database and the
     * clock as they are NOW, so a task finished, cancelled, deleted, given a new due date, or reassigned since the claim is not reminded about,
     * a reminder whose window has closed (a backed-up queue) is not sent late, and one email per person is built from what is still valid.
     * The reminders still valid are recorded in {@code kept}, the ones that went stale in {@code expired}.
     */
    private Collection<Notice> noticesFor(List<Claimed> claimed, Set<UUID> kept, Set<UUID> expired, Map<UUID, List<ReminderLine>> lines) {
        ZonedDateTime now = deliveryNow();   // read here, on the worker, not when the scheduler claimed the reminder
        Map<UUID, Task> tasks = new java.util.HashMap<>();
        Map<UUID, Set<UUID>> assigneesByTask = new java.util.HashMap<>();
        Map<UUID, Set<UUID>> managersByTask = new java.util.HashMap<>();
        for (Claimed c : claimed) {
            Pending p = c.pending();
            Task task = tasks.computeIfAbsent(p.taskId(), id -> taskRepository.findById(id).orElse(null));
            if (task == null || task.getStatus() == null || !OPEN.contains(task.getStatus())) continue;
            if (!p.dueKey().equals(TaskReminderRules.dueKey(task.getDueDate(), task.getDeadlineTime()))) continue;
            // The reminder's own scheduled moment against the current time: still inside its window, and the task still not past it (due-soon)
            if (!TaskReminderRules.dueAt(task.getDueDate(), task.getDeadlineTime(), now, grace).contains(new Fire(p.kind(), p.slot()))) {
                expired.add(c.logId());
                continue;
            }
            Set<UUID> assignees = assigneesByTask.computeIfAbsent(p.taskId(), this::assigneeIds);
            boolean valid;
            if (p.manager()) {
                Set<UUID> managerIds = managersByTask.computeIfAbsent(p.taskId(), id -> managerIdsOf(task));
                valid = managerIds.contains(p.userId()) && (!p.unassignedOnly() || assignees.isEmpty());
            } else {
                valid = assignees.contains(p.userId());
            }
            if (!valid) continue;
            kept.add(c.logId());
            lines.computeIfAbsent(p.userId(), id -> new ArrayList<>()).add(new ReminderLine(
                    task.getTitle(),
                    task.getDepartment() == null ? null : task.getDepartment().getName(),
                    whenText(p.kind()),
                    dueText(task),
                    p.manager() && p.unassignedOnly() ? "not assigned to anyone yet" : null));
        }
        List<Notice> notices = new ArrayList<>();
        for (Map.Entry<UUID, List<ReminderLine>> entry : lines.entrySet()) {
            entry.getValue().sort(Comparator.comparing(ReminderLine::title, String.CASE_INSENSITIVE_ORDER));
            notices.add(new Notice(entry.getKey(), NotificationEmailTemplates.taskReminders(entry.getValue())));
        }
        return notices;
    }

    /**
     * Accounts that share one email address are served by ONE email: every eligible account's reminder lines, combined (identical lines once,
     * sorted by title). Each account keeps its own eligibility and preference, decided before this is called.
     */
    private static EmailContent mergedContent(Map<UUID, List<ReminderLine>> lines, List<UUID> userIds) {
        Set<ReminderLine> combined = new LinkedHashSet<>();
        for (UUID id : userIds) combined.addAll(lines.getOrDefault(id, List.of()));
        if (combined.isEmpty()) return null;
        List<ReminderLine> sorted = new ArrayList<>(combined);
        sorted.sort(Comparator.comparing(ReminderLine::title, String.CASE_INSENSITIVE_ORDER));
        return NotificationEmailTemplates.taskReminders(sorted);
    }

    /**
     * On the worker, after delivery was attempted: each log row is updated in its OWN transaction (one retry), so a failed write for one row
     * cannot undo or block another. A row whose write still fails stays CLAIMED: after an hour it becomes UNKNOWN, which keeps counting toward
     * the overdue cap, so an email the provider accepted can never be forgotten and exceeded.
     */
    private void finish(List<Claimed> claimed, Set<UUID> kept, Set<UUID> expired, Map<UUID, Outcome> outcomes) {
        LocalDateTime stamp = deliveryNow().toLocalDateTime();
        int unrecorded = 0;
        for (Claimed c : claimed) {
            ReminderStatus status;
            if (!kept.contains(c.logId())) {
                status = expired.contains(c.logId()) ? ReminderStatus.EXPIRED : ReminderStatus.SKIPPED;
            } else {
                // No outcome for a reminder that was due means we do not know what happened: never assume it was not sent
                Outcome outcome = outcomes.getOrDefault(c.pending().userId(), Outcome.UNKNOWN);
                status = switch (outcome) {
                    case SENT -> ReminderStatus.SENT;
                    case SKIPPED -> ReminderStatus.SKIPPED;
                    case FAILED -> ReminderStatus.FAILED;
                    case UNKNOWN -> ReminderStatus.UNKNOWN;
                };
            }
            if (!record(c.logId(), status, stamp)) unrecorded++;
        }
        if (unrecorded > 0) {
            log.error("{} task reminder log row(s) could not be updated; they stay CLAIMED and become UNKNOWN (still counted by the overdue cap)", unrecorded);
        }
    }

    /** One row's outcome, written in its own short transaction (package-private so a test can make a single write fail). */
    int writeOutcome(UUID logId, ReminderStatus status, LocalDateTime stamp) {
        return newTransaction(false).execute(s -> logRepository.finishClaim(logId, status, stamp));
    }

    /** Writes one row's outcome, in its own transaction, with one retry. True when the write succeeded (or the row is gone or already final). */
    private boolean record(UUID logId, ReminderStatus status, LocalDateTime stamp) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                writeOutcome(logId, status, stamp);
                return true;
            } catch (RuntimeException e) {
                if (attempt == 2) log.error("Recording a task reminder outcome failed: {}", e.getClass().getSimpleName());
            }
        }
        return false;
    }

    private static String whenText(ReminderKind kind) {
        return switch (kind) {
            case DUE_24H -> "Due in 24 hours";
            case DUE_2H -> "Due in 2 hours";
            case DUE_TODAY -> "Due today";
            case OVERDUE -> "Overdue";
        };
    }

    private static String dueText(Task task) {
        String date = String.valueOf(task.getDueDate());
        return task.getDeadlineTime() == null ? date : date + " " + task.getDeadlineTime().withSecond(0).withNano(0) + " IST";
    }
}
