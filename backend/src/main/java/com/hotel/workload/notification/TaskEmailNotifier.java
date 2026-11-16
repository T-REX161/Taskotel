package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationEmailTemplates.TaskInfo;
import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.task.Task;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The emails about a task's assignment (category TASK_ASSIGNED). It only describes the event: the facts are copied while the
 * transaction is still open, and {@link NotificationService} sends after commit, to whoever is eligible at that moment.
 */
@Component
public class TaskEmailNotifier {

    private final NotificationService notifications;

    public TaskEmailNotifier(NotificationService notifications) {
        this.notifications = notifications;
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
