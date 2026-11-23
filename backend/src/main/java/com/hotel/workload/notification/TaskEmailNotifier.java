package com.hotel.workload.notification;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.NotificationEmailTemplates.TaskInfo;
import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.ManagerViewGrantRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The emails about a task: its assignment (category TASK_ASSIGNED) and its status changes (category TASK_STATUS). It only describes the
 * event: the facts are copied while the transaction is still open, and {@link NotificationService} sends after commit, to whoever is
 * eligible at that moment.
 */
@Component
public class TaskEmailNotifier {

    private final NotificationService notifications;
    private final AssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final ManagerViewGrantRepository grantRepository;
    private final DepartmentRepository departmentRepository;

    public TaskEmailNotifier(NotificationService notifications, AssignmentRepository assignmentRepository, UserRepository userRepository,
                             StaffRepository staffRepository, ManagerViewGrantRepository grantRepository, DepartmentRepository departmentRepository) {
        this.notifications = notifications;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.grantRepository = grantRepository;
        this.departmentRepository = departmentRepository;
    }

    /**
     * A task's status really changed from {@code previous} to its current value (the caller only calls this for a genuine change).
     *
     * <p>Who is told, decided after commit from the database as it is then:
     * <ul>
     *   <li>the Staff member(s) the task is assigned to;</li>
     *   <li>the Managers responsible for the task's department: those whose primary department it is and those with an active
     *       Administrator grant for it, each only while their account, linked Staff record and primary department are active (a Manager
     *       with an inactive primary department has no department access, so receives nothing);</li>
     *   <li>when the task is COMPLETED, also the person who originally assigned it (Admin or Manager);</li>
     *   <li>never the person who made the change (they know), and nobody twice. Admins are not broadcast to.</li>
     * </ul>
     * Each recipient's own TASK_STATUS preference decides whether the email is sent.
     *
     * @param actorUsername the signed-in account that made the change
     */
    public void taskStatusChanged(Task task, TaskStatus previous, String actorUsername) {
        if (task == null || task.getId() == null || task.getStatus() == null || task.getStatus() == previous) return;
        UUID taskId = task.getId();
        String departmentCode = task.getDepartment() == null ? null : task.getDepartment().getCode();
        User actor = actorUsername == null ? null : userRepository.findByUsername(actorUsername).orElse(null);
        UUID actorId = actor == null ? null : actor.getId();
        boolean completed = task.getStatus() == TaskStatus.COMPLETED;
        // A completed task has its own email (no "previous / new status"); every other change keeps the ordinary status-update email
        EmailContent content = completed
                ? NotificationEmailTemplates.taskCompleted(describe(task), completedByLabel(actor))
                : NotificationEmailTemplates.taskStatusChanged(
                        describe(task), previous == null ? "None" : previous.getDisplayName(), task.getStatus().getDisplayName(), actorLabel(actor));

        notifications.sendAfterCommit(NotificationCategory.TASK_STATUS, () -> {
            Set<UUID> recipients = new LinkedHashSet<>();
            for (var assignment : assignmentRepository.findAllByTaskId(taskId)) {
                if (assignment.getStaff() != null && assignment.getStaff().getUser() != null) recipients.add(assignment.getStaff().getUser().getId());
            }
            if (departmentCode != null) {
                departmentRepository.findByCode(departmentCode).ifPresent(department -> recipients.addAll(managersOf(department)));
            }
            if (completed) {
                // The person who ORIGINALLY assigned the task (an Admin or a Manager), as recorded when the assignment was made; a later
                // reassignment never changes it. Unknown (older assignments) adds nobody.
                for (var assignment : assignmentRepository.findAllByTaskId(taskId)) {
                    if (assignment.getAssignedByUserId() != null) recipients.add(assignment.getAssignedByUserId());
                }
            }
            if (actorId != null) recipients.remove(actorId);
            List<Notice> notices = new ArrayList<>();
            for (UUID id : recipients) notices.add(new Notice(id, content));
            return notices;
        });
    }

    /**
     * Who completed the task, for the completion email: the person's display name and role, "Mia Chen (Staff)". The display name is the one
     * from their profile, else the name on their Staff record; the sign-in username is never shown. With no name known, just the role.
     */
    private String completedByLabel(User actor) {
        if (actor == null) return null;
        String role = switch (actor.getRole()) {
            case ADMIN -> "Admin";
            case MANAGER -> "Manager";
            default -> "Staff";
        };
        String name = actor.getDisplayName();
        if (name == null || name.isBlank()) name = staffRepository.findByUserUsername(actor.getUsername()).map(Staff::getName).orElse(null);
        return name == null || name.isBlank() ? role : name.trim() + " (" + role + ")";
    }

    /** Who made the change, for the email: a Staff or Manager by name, an Administrator by role only (no account name is shown). */
    private String actorLabel(User actor) {
        if (actor == null) return null;
        if (actor.getRole() == Role.ADMIN) return "An administrator";
        String name = staffRepository.findByUserUsername(actor.getUsername()).map(Staff::getName).orElse(null);
        String role = actor.getRole() == Role.MANAGER ? "Manager" : "Staff";
        return name == null || name.isBlank() ? role : name + " (" + role + ")";
    }

    /**
     * A task was assigned (or support staff were added to it) by {@code actorUsername}. Who is told, decided after commit:
     * <ol>
     *   <li>every NEW assignee (the caller passes only the people just assigned, so existing assignees are never told again);</li>
     *   <li>the Manager(s) responsible for the task's department (primary department or active grant, the same rules as the status email);
     *       the assigning Manager is one of them when it is their department, and then receives one email, not two;</li>
     *   <li>the Admin, only when that Admin made the assignment: other Admins and other Managers are never told.</li>
     * </ol>
     * One email per account (and per address). Each recipient's own TASK_ASSIGNED preference decides.
     */
    public void taskAssigned(Task task, Collection<Staff> assignees, String actorUsername) {
        if (task == null || assignees == null) return;
        announce(task, assignees, null, actorUsername, true);
    }

    /** Kept for callers that have no signed-in actor: assignees and the department Manager(s) are told, no Admin. */
    public void taskAssigned(Task task, Collection<Staff> assignees) {
        taskAssigned(task, assignees, null);
    }

    /**
     * A task moved from one person to another by {@code actorUsername}: the new assignee is told it is theirs, the previous one that it no
     * longer is, and the department Manager(s) and the acting Admin hear who holds it now. With no new assignee (a person was removed) only
     * the previous holder is told, as before.
     */
    public void taskReassigned(Task task, Staff previous, Staff next, String actorUsername) {
        if (task == null) return;
        announce(task, next == null ? List.of() : List.of(next), previous, actorUsername, next != null);
    }

    private void announce(Task task, Collection<Staff> assignees, Staff previous, String actorUsername, boolean tellOversight) {
        TaskInfo info = describe(task);
        EmailContent assigneeContent = NotificationEmailTemplates.taskAssigned(info);
        UUID previousId = userId(previous);
        // Captured now, while the transaction is open: nothing lazy is read after commit
        List<UUID> assigneeIds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Staff assignee : assignees) {
            UUID id = userId(assignee);
            if (id != null) assigneeIds.add(id);
            if (assignee != null && assignee.getName() != null && !assignee.getName().isBlank()) names.add(assignee.getName());
        }
        String departmentCode = task.getDepartment() == null ? null : task.getDepartment().getCode();
        User actor = actorUsername == null ? null : userRepository.findByUsername(actorUsername).orElse(null);
        UUID adminActorId = actor != null && actor.getRole() == Role.ADMIN ? actor.getId() : null;
        EmailContent overview = NotificationEmailTemplates.taskAssignedOverview(info, String.join(", ", names), actorLabel(actor));

        notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, () -> {
            // First notice wins for an account, so the person's own "this is yours" email beats any overview they would also qualify for
            Map<UUID, Notice> notices = new LinkedHashMap<>();
            for (UUID id : assigneeIds) notices.putIfAbsent(id, new Notice(id, assigneeContent));
            if (previousId != null && !notices.containsKey(previousId)) {
                notices.put(previousId, new Notice(previousId, NotificationEmailTemplates.taskUnassigned(info)));
            }
            if (tellOversight) {
                Set<UUID> oversight = new LinkedHashSet<>();
                if (departmentCode != null) departmentRepository.findByCode(departmentCode).ifPresent(department -> oversight.addAll(managersOf(department)));
                if (adminActorId != null) oversight.add(adminActorId);
                for (UUID id : oversight) notices.putIfAbsent(id, new Notice(id, overview));
            }
            return notices.values();
        });
    }

    /** The Managers responsible for a department right now: primary-department Managers and active-grant Managers, valid accounts only. */
    public Set<UUID> managersOf(com.hotel.workload.staff.Department department) {
        Set<UUID> managers = new LinkedHashSet<>();
        managers.addAll(staffRepository.findValidManagerUserIdsByPrimaryDepartment(department));
        managers.addAll(grantRepository.findValidManagerUserIdsByGrantedDepartment(department));
        return managers;
    }

    private static UUID userId(Staff staff) {
        return staff == null || staff.getUser() == null ? null : staff.getUser().getId();
    }

    private static TaskInfo describe(Task task) {
        return new TaskInfo(
                task.getTitle(),
                task.getDescription(),
                task.getPriority() == null ? null : task.getPriority().name(),
                task.getDueDate() == null ? null : task.getDueDate().toString(),
                task.getDeadlineTime() == null ? null : task.getDeadlineTime().toString(),
                task.getEstimatedDuration(),
                task.getDepartment() == null ? null : task.getDepartment().getName());
    }
}
