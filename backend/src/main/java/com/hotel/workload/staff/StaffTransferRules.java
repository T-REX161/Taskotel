package com.hotel.workload.staff;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The one place that decides whether a staff member may be moved to another department: they must hold no active task
 * (PENDING, ASSIGNED, IN_PROGRESS) that belongs to a different department. Used by the ordinary staff edit and by the dedicated
 * Manager department assignment, so the two can never drift apart.
 */
final class StaffTransferRules {

    static final List<TaskStatus> ACTIVE_TASK_STATUSES = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    private StaffTransferRules() {
    }

    /** Active tasks of the staff member whose department differs from the target. Tasks without a department never conflict. */
    static List<Task> activeTasksOutside(AssignmentRepository assignmentRepository, Staff staff, Department target) {
        if (assignmentRepository == null) {
            return List.of();
        }
        return assignmentRepository.findByStaffIdOrderByAssignedAtDesc(staff.getId()).stream()
                .map(Assignment::getTask)
                .filter(task -> task != null && ACTIVE_TASK_STATUSES.contains(task.getStatus()))
                .filter(task -> task.getDepartment() != null && !task.getDepartment().equals(target))
                .collect(Collectors.toList());
    }

    /** @throws ResponseStatusException 409 when the move is blocked by active tasks in another department */
    static void requireNoConflictingTasks(AssignmentRepository assignmentRepository, Staff staff, Department target) {
        if (target == null) {
            return; // clearing a department is a different rule (any active assignment blocks it), made by the caller
        }
        List<Task> conflicting = activeTasksOutside(assignmentRepository, staff, target);
        if (!conflicting.isEmpty()) {
            String taskList = conflicting.stream()
                    .map(t -> t.getTitle() + " (" + t.getDepartment().getName() + ")")
                    .collect(Collectors.joining(", "));
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "Cannot move %s to %s because they have active tasks in another department: %s. "
                            + "Reassign or complete those tasks first.",
                    staff.getName(), target.getName(), taskList));
        }
    }
}
