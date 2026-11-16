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
import java.util.LinkedHashSet;
import java.util.List;
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
        EmailContent content = NotificationEmailTemplates.taskStatusChanged(
                describe(task), previous == null ? "None" : previous.getDisplayName(), task.getStatus().getDisplayName(), actorLabel(actor));

        notifications.sendAfterCommit(NotificationCategory.TASK_STATUS, () -> {
            Set<UUID> recipients = new LinkedHashSet<>();
            for (var assignment : assignmentRepository.findAllByTaskId(taskId)) {
                if (assignment.getStaff() != null && assignment.getStaff().getUser() != null) recipients.add(assignment.getStaff().getUser().getId());
            }
            if (departmentCode != null) {
                departmentRepository.findByCode(departmentCode).ifPresent(department -> {
                    recipients.addAll(staffRepository.findValidManagerUserIdsByPrimaryDepartment(department));
                    recipients.addAll(grantRepository.findValidManagerUserIdsByGrantedDepartment(department));
                });
            }
            if (actorId != null) recipients.remove(actorId);
            List<Notice> notices = new ArrayList<>();
            for (UUID id : recipients) notices.add(new Notice(id, content));
            return notices;
        });
    }

    /** Who made the change, for the email: a Staff or Manager by name, an Administrator by role only (no account name is shown). */
    private String actorLabel(User actor) {
        if (actor == null) return null;
        if (actor.getRole() == Role.ADMIN) return "An administrator";
        String name = staffRepository.findByUserUsername(actor.getUsername()).map(Staff::getName).orElse(null);
        String role = actor.getRole() == Role.MANAGER ? "Manager" : "Staff";
        return name == null || name.isBlank() ? role : name + " (" + role + ")";
    }

    /** A task was assigned: every new assignee is told, once. */
    public void taskAssigned(Task task, Collection<Staff> assignees) {
        if (task == null || assignees == null) return;
        EmailContent content = NotificationEmailTemplates.taskAssigned(describe(task));
        List<Notice> notices = new ArrayList<>();
        for (Staff assignee : assignees) {
            UUID userId = userId(assignee);
            if (userId != null) notices.add(new Notice(userId, content));
        }
        notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, notices);
    }

    /** A task moved from one person to another: the new assignee is told it is theirs, the previous one that it no longer is. */
    public void taskReassigned(Task task, Staff previous, Staff next) {
        if (task == null) return;
        TaskInfo info = describe(task);
        List<Notice> notices = new ArrayList<>();
        UUID nextId = userId(next);
        if (nextId != null) notices.add(new Notice(nextId, NotificationEmailTemplates.taskAssigned(info)));
        UUID previousId = userId(previous);
        if (previousId != null && !previousId.equals(nextId)) notices.add(new Notice(previousId, NotificationEmailTemplates.taskUnassigned(info)));
        notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, notices);
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
