package com.hotel.workload.notification;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.ManagerViewGrantRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.WorkloadStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Workload alert emails: tells a Staff member, and the Managers of their home department, when the workload goes UP into HIGH or AT_RISK.
 *
 * <p><b>Levels.</b> The existing thresholds ({@link WorkloadDto#deriveStatus}: 120, 240, 360 minutes) over the estimated minutes of the
 * active assignments (tasks PENDING, ASSIGNED or IN_PROGRESS; no duration counts as 0). Two audiences, tracked independently because they
 * can differ:
 * <ul>
 *   <li><b>STAFF</b>: the person's GLOBAL workload (all their active assignments, loan work included), as the Staff member sees it. Only
 *       that Staff member is told.</li>
 *   <li><b>MANAGER</b>: only the work of the person's HOME department (active assignments of tasks of that department), which is what a
 *       Manager's Workload page shows. Loan work is never part of it, and nothing about it reaches a Manager. The Managers of the home
 *       department are told (the existing primary-department and active-grant rules). Admins are never told.</li>
 * </ul>
 *
 * <p><b>When an alert is sent.</b> Only when the level goes up into HIGH or AT_RISK: LOW/NORMAL to HIGH, LOW/NORMAL to AT_RISK, HIGH to
 * AT_RISK. Staying at a level, a change between LOW and NORMAL, and any recovery send nothing; a later rise alerts again.
 *
 * <p><b>Flow</b> (every step after the first is on the existing bounded notification worker):
 * <ol>
 *   <li>A workload-changing operation calls {@link #track} BEFORE it changes anything. That records the current levels (used only as the
 *       baseline when no state row exists yet, so a workload that was already high does not alert on its first change) and registers the
 *       evaluation to run after the business transaction commits. A rolled-back operation never runs it.</li>
 *   <li>On the worker, per staff member and audience, a SHORT transaction locks the state row, compares the latest committed level with the
 *       stored one, stores the new level, and COMMITS.</li>
 *   <li>Only then, with no transaction and no lock held, the recipients are resolved and the emails are sent.</li>
 * </ol>
 *
 * <p><b>First-time creation is safe.</b> The row is inserted first, in its own transaction, relying on the unique constraint (staff_id,
 * audience): if another worker or application instance inserted it at the same moment, the constraint rejects this insert, which is simply
 * ignored. Only then is the row, which now certainly exists, locked with SELECT ... FOR UPDATE. A row that did not exist is never locked.
 * Whoever updates the level inside the lock is the only one that alerts; a competing evaluation waits, then sees the stored level is
 * current and sends nothing.
 *
 * <p><b>Limits.</b> Best effort, like every notification: the state is advanced BEFORE the email is sent, so an email that is dropped or
 * fails (or a crash between the two) is not repeated. An event dropped by a full queue or a shutdown is only logged; the next
 * workload-changing event for that person evaluates the latest committed workload again. Events can be processed out of commit order, and
 * the evaluation always uses the latest committed workload, so an intermediate level can be skipped.
 */
@Service
public class WorkloadAlertService {

    private static final Logger log = LoggerFactory.getLogger(WorkloadAlertService.class);

    static final String AUDIENCE_STAFF = "STAFF";
    static final String AUDIENCE_MANAGER = "MANAGER";
    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    /** A level with the figures behind it. */
    record Figures(WorkloadStatus level, long minutes, long tasks) {}

    /** The levels of one staff member before a change; {@code manager} is null when they have no home department. */
    record Levels(WorkloadStatus staff, WorkloadStatus manager) {}

    private record Decision(String audience, Staff staff, UUID staffUserId, String departmentCode, Figures figures) {}

    private final NotificationService notifications;
    private final StaffRepository staffRepository;
    private final AssignmentRepository assignmentRepository;
    private final WorkloadAlertStateRepository stateRepository;
    private final ManagerViewGrantRepository grantRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;

    public WorkloadAlertService(NotificationService notifications, StaffRepository staffRepository, AssignmentRepository assignmentRepository,
                                WorkloadAlertStateRepository stateRepository, ManagerViewGrantRepository grantRepository,
                                DepartmentRepository departmentRepository, UserRepository userRepository,
                                PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.stateRepository = stateRepository;
        this.grantRepository = grantRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.transactionManager = transactionManager;
    }

    // ── called by the workload-changing operations ───────────────────────────────────────────────────────────────

    /** The staff members who hold the task (read before the operation changes or deletes anything). Call {@link #track} with the result. */
    public List<UUID> holdersOf(UUID taskId) {
        List<UUID> ids = new ArrayList<>();
        assignmentRepository.findAllByTaskId(taskId).forEach(a -> {
            if (a.getStaff() != null) ids.add(a.getStaff().getId());
        });
        return ids;
    }

    /**
     * Call BEFORE an operation that can change these people's workload. Records their current levels and registers the evaluation for after
     * the surrounding transaction commits (on the notification worker). Never throws: it must not be able to break the operation.
     */
    public void track(Collection<UUID> staffIds) {
        try {
            if (staffIds == null || staffIds.isEmpty()) return;
            Map<UUID, Levels> before = new LinkedHashMap<>();
            for (UUID id : new LinkedHashSet<>(staffIds)) {
                if (id == null) continue;
                Staff staff = staffRepository.findById(id).orElse(null);
                if (staff == null) continue;
                WorkloadStatus global = figuresGlobal(id).level();
                WorkloadStatus home = staff.getDepartment() == null ? null : figuresHome(id).level();
                before.put(id, new Levels(global, home));
            }
            if (before.isEmpty()) return;
            String actor = currentUsername();
            notifications.runAfterCommit("workload alert", () -> evaluate(before, actor));
        } catch (RuntimeException e) {
            log.error("Workload alert tracking failed: {}", e.getClass().getSimpleName());
        }
    }

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    // ── the evaluation, on the worker, after the business transaction has committed ──────────────────────────────

    /** Evaluates every tracked staff member; one failing person never stops the others. Package-private so tests can drive it directly. */
    void evaluate(Map<UUID, Levels> before, String actorUsername) {
        UUID actorId = actorUsername == null ? null : userRepository.findByUsername(actorUsername).map(User::getId).orElse(null);
        for (Map.Entry<UUID, Levels> entry : before.entrySet()) {
            try {
                // 1. state, in short transactions that are finished before anything is sent
                Decision staffDecision = step(entry.getKey(), AUDIENCE_STAFF, entry.getValue().staff());
                Decision managerDecision = step(entry.getKey(), AUDIENCE_MANAGER, entry.getValue().manager());
                // 2. recipients and emails, with no transaction and no lock
                send(staffDecision, managerDecision, actorId);
            } catch (RuntimeException e) {
                log.error("A workload alert evaluation failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * One audience of one staff member: make sure the state row exists, then lock it, compare, store the new level and commit. Returns the
     * alert to send (after the transaction has finished), or null when nothing is to be sent.
     */
    private Decision step(UUID staffId, String audience, WorkloadStatus baseline) {
        ensureRow(staffId, audience, baseline);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> {
            Staff staff = staffRepository.findById(staffId).orElse(null);
            WorkloadAlertState row = stateRepository.lockByStaffIdAndAudience(staffId, audience).orElse(null);
            if (staff == null || row == null) return null; // the staff member was removed meanwhile
            boolean managerAudience = AUDIENCE_MANAGER.equals(audience);
            if (managerAudience && staff.getDepartment() == null) return null;
            Figures now = managerAudience ? figuresHome(staffId) : figuresGlobal(staffId);
            WorkloadStatus stored = WorkloadStatus.valueOf(row.getLevel());
            if (stored == now.level()) return null;
            row.changeLevel(now.level().name());
            stateRepository.save(row);
            if (!isRise(stored, now.level()) || !staff.isActive()) return null;
            return new Decision(audience, staff, staff.getUser() == null ? null : staff.getUser().getId(),
                    staff.getDepartment() == null ? null : staff.getDepartment().getCode(), now);
        });
    }

    /**
     * Creates the state row if it does not exist, in its own transaction. If another worker or instance created it first, the unique
     * constraint rejects this insert; that is expected and ignored. (The row is created before it is locked: a row that does not exist yet
     * cannot be locked.)
     */
    private void ensureRow(UUID staffId, String audience, WorkloadStatus baseline) {
        if (stateRepository.findByStaffIdAndAudience(staffId, audience).isPresent()) return;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            template.executeWithoutResult(status -> {
                if (AUDIENCE_MANAGER.equals(audience) && !staffHasDepartment(staffId)) return;
                WorkloadStatus start = baseline != null ? baseline : WorkloadStatus.LOW;
                stateRepository.saveAndFlush(new WorkloadAlertState(staffId, audience, start.name()));
            });
        } catch (DataIntegrityViolationException e) {
            // lost the race: the row exists now, which is all that is needed
        }
    }

    private boolean staffHasDepartment(UUID staffId) {
        return staffRepository.findById(staffId).map(s -> s.getDepartment() != null).orElse(false);
    }

    /** Only a rise into HIGH or AT_RISK alerts: LOW and NORMAL count as one "normal" band. */
    static boolean isRise(WorkloadStatus from, WorkloadStatus to) {
        return rank(to) > rank(from) && rank(to) >= 1;
    }

    private static int rank(WorkloadStatus level) {
        switch (level) {
            case HIGH: return 1;
            case AT_RISK: return 2;
            default: return 0;
        }
    }

    // ── the figures: the existing thresholds over the existing active-assignment rule ─────────────────────────────

    Figures figuresGlobal(UUID staffId) {
        return figures(assignmentRepository.activeWorkloadOfStaff(staffId, ACTIVE));
    }

    Figures figuresHome(UUID staffId) {
        return figures(assignmentRepository.activeHomeDepartmentWorkloadOfStaff(staffId, ACTIVE));
    }

    private static Figures figures(List<Object[]> rows) {
        long minutes = rows.isEmpty() || rows.get(0)[0] == null ? 0 : ((Number) rows.get(0)[0]).longValue();
        long tasks = rows.isEmpty() || rows.get(0)[1] == null ? 0 : ((Number) rows.get(0)[1]).longValue();
        return new Figures(WorkloadDto.deriveStatus((int) Math.min(minutes, Integer.MAX_VALUE)), minutes, tasks);
    }

    // ── recipients and emails (no transaction, no lock) ──────────────────────────────────────────────────────────

    private void send(Decision staffDecision, Decision managerDecision, UUID actorId) {
        List<Notice> notices = new ArrayList<>();
        // The Staff member's own alert first: if a person were ever in both lists, theirs about themselves is the one kept
        if (staffDecision != null && staffDecision.staffUserId() != null && !staffDecision.staffUserId().equals(actorId)) {
            notices.add(new Notice(staffDecision.staffUserId(),
                    NotificationEmailTemplates.workloadAlertForStaff(staffDecision.figures().level().name(), staffDecision.figures().minutes(),
                            staffDecision.figures().tasks())));
        }
        if (managerDecision != null && managerDecision.departmentCode() != null) {
            Department department = departmentRepository.findByCode(managerDecision.departmentCode()).orElse(null);
            if (department != null) {
                EmailContent content = NotificationEmailTemplates.workloadAlertForManager(managerDecision.staff().getName(), department.getName(),
                        managerDecision.figures().level().name(), managerDecision.figures().minutes(), managerDecision.figures().tasks());
                Set<UUID> managers = new LinkedHashSet<>();
                managers.addAll(staffRepository.findValidManagerUserIdsByPrimaryDepartment(department));
                managers.addAll(grantRepository.findValidManagerUserIdsByGrantedDepartment(department));
                if (actorId != null) managers.remove(actorId);
                for (UUID id : managers) notices.add(new Notice(id, content));
            }
        }
        if (!notices.isEmpty()) notifications.sendNow(NotificationCategory.WORKLOAD_ALERT, notices);
    }
}
