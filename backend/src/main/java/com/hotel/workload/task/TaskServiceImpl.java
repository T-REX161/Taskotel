package com.hotel.workload.task;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentResolver;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final AssignmentRepository assignmentRepository;
    private final DepartmentResolver departmentResolver;
    private final SupportLifecycle supportLifecycle;
    private final CurrentUserScopeResolver scopeResolver;

    private static final String OWN_DEPARTMENT_ONLY_MESSAGE = "Managers can only create tasks in their own department.";
    private static final String NO_DEPARTMENT_CHANGE_MESSAGE = "Managers cannot move a task to another department or remove its department.";

    public TaskServiceImpl(TaskRepository taskRepository,
                           UserRepository userRepository,
                           StaffRepository staffRepository,
                           AssignmentRepository assignmentRepository,
                           DepartmentResolver departmentResolver,
                           SupportLifecycle supportLifecycle,
                           CurrentUserScopeResolver scopeResolver) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.departmentResolver = departmentResolver;
        this.supportLifecycle = supportLifecycle;
        this.scopeResolver = java.util.Objects.requireNonNull(scopeResolver, "scopeResolver");
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskDto> getAllTasks() {
        // The scope decides, from the database, what this caller may see; no sign-in is 401 and an invalid Manager is 403
        DepartmentScope scope = scopeResolver.resolve();

        if (scope.isAll()) {
            return taskRepository.findAll().stream()
                    .map(this::mapToDto)
                    .collect(Collectors.toList());
        }
        if (scope.isDepartment()) {
            // A Manager: the department is part of the query. Tasks with no department are never returned.
            return taskRepository.findByDepartment(scope.department()).stream()
                    .map(this::mapToDto)
                    .collect(Collectors.toList());
        }

        // Staff: unchanged. Only the tasks assigned to the signed-in staff member.
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return staffRepository.findByUserUsername(username)
                .map(staff -> taskRepository.findTasksByStaffId(staff.getId()).stream()
                        .map(this::mapToDto)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    /**
     * The scope for changing tasks: global for an administrator, one department for a valid Manager. A Staff member is refused, and an
     * invalid Manager (403) or a missing sign-in (401) is refused by the resolver itself.
     */
    private DepartmentScope requireTaskManagementScope() {
        DepartmentScope scope = scopeResolver.resolve();
        if (!scope.isAll() && !scope.isDepartment()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to manage tasks.");
        }
        return scope;
    }

    /**
     * Locks and loads the task for a change. An administrator uses the existing lock. A Manager uses the department-scoped lock, so a task
     * of another department, or one with no department, is reported as not found, exactly like an id that does not exist, and is never locked.
     */
    private Task lockTask(DepartmentScope scope, UUID id) {
        if (scope.isAll()) {
            taskRepository.lockByIds(Set.of(id));
            return taskRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        }
        return taskRepository.lockByIdAndDepartment(id, scope.department())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }

    @Override
    @Transactional
    public TaskDto createTask(CreateTaskRequest request) {
        DepartmentScope scope = requireTaskManagementScope();
        // A Manager creates tasks in their own department only. A blank department means their own; any other value is refused before
        // anything is looked up or saved, so the refusal says nothing about whether that department exists.
        if (scope.isDepartment() && request.getDepartment() != null && !request.getDepartment().isBlank()
                && !request.getDepartment().trim().equals(scope.departmentCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, OWN_DEPARTMENT_ONLY_MESSAGE);
        }
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User createdBy = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));

        Task task = new Task();
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setCategory(request.getCategory());
        task.setPriority(request.getPriority());
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(request.getEstimatedDuration());
        task.setDueDate(request.getDueDate());
        task.setDeadlineTime(request.getDeadlineTime());
        // For a Manager the department is the one resolved from their own records, never the one from the request
        task.setDepartment(scope.isDepartment() ? scope.department() : departmentResolver.optionalActive(request.getDepartment()));
        task.setCreatedBy(createdBy);
        // createdAt / updatedAt handled by @PrePersist on the entity
        task = taskRepository.save(task);
        return mapToDto(task);
    }

    @Override
    @Transactional
    public TaskDto updateTask(UUID id, UpdateTaskRequest request) {
        DepartmentScope scope = requireTaskManagementScope();
        // Lock the task before reading it, as assignment, reassignment and support do (task first, then staff / support), so a
        // status change and an assignment of this task are applied one after the other, each seeing what the other committed.
        // A Manager can only lock (and so only reach) a task of their own department.
        Task task = lockTask(scope, id);
        // Only an administrator moves a task to another department or removes its department. This is refused before any field changes.
        if (scope.isDepartment() && !isCurrentDepartment(task, request.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NO_DEPARTMENT_CHANGE_MESSAGE);
        }

        // Reject before any field changes so a refused update leaves the task untouched
        // Unchanged department: kept even if it is now inactive. Changed department: must be an active one.
        Department requestedDepartment = departmentResolver.forUpdate(task.getDepartment(), request.getDepartment(), false);
        rejectDepartmentChangeThatConflictsWithAssignee(task, requestedDepartment);
        rejectStatusChangeThatConflictsWithAssignment(task, request.getStatus());

        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setCategory(request.getCategory());
        task.setPriority(request.getPriority());
        if (request.getStatus() != null) {
            task.setStatus(request.getStatus());
        }
        task.setEstimatedDuration(request.getEstimatedDuration());
        task.setDueDate(request.getDueDate());
        task.setDeadlineTime(request.getDeadlineTime());
        task.setDepartment(requestedDepartment);

        task = taskRepository.saveAndFlush(task);
        // Temporary cross-department support ends when its task is completed, cancelled or moved
        supportLifecycle.onTaskChanged(task);
        return mapToDto(task);
    }

    /**
     * A task's department may only change to the department of its current assignee.
     * Only checked when the department actually changes, so editing other fields of an
     * existing record keeps working. A null department means "no restriction", matching
     * the assignment rules.
     */
    private void rejectDepartmentChangeThatConflictsWithAssignee(Task task, Department requestedDepartment) {
        if (requestedDepartment == null || Objects.equals(requestedDepartment, task.getDepartment())) {
            return;
        }

        for (Assignment assignment : assignmentRepository.findAllByTaskId(task.getId())) {
            Staff assignee = assignment.getStaff();
            if (assignee != null && assignee.getDepartment() != null && !assignee.getDepartment().equals(requestedDepartment)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                        "Cannot change this task's department to %s because it is assigned to %s (%s). "
                                + "Reassign the task to a %s staff member first, or keep its current department.",
                        requestedDepartment.getName(),
                        assignee.getName(),
                        assignee.getDepartment().getName(),
                        requestedDepartment.getName()));
            }
        }
    }

    /**
     * Keeps a task's status consistent with whether it has an assignment:
     * <ul>
     *   <li>COMPLETED and CANCELLED tasks cannot move to any other status.</li>
     *   <li>A task with an assignment cannot go back to PENDING.</li>
     *   <li>A task without an assignment can only be PENDING or CANCELLED.</li>
     * </ul>
     * Only checked when the status actually changes, so editing other fields of an existing
     * record keeps working. Never creates, deletes or reassigns an assignment.
     */
    private void rejectStatusChangeThatConflictsWithAssignment(Task task, TaskStatus requestedStatus) {
        TaskStatus currentStatus = task.getStatus();
        if (requestedStatus == null || requestedStatus == currentStatus) {
            return;
        }

        if (currentStatus != null && currentStatus.isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "This task is %s and cannot be changed to %s. Finished tasks cannot be reopened; "
                            + "create a new task if more work is needed.",
                    currentStatus.getDisplayName(), requestedStatus.getDisplayName()));
        }

        boolean hasAssignment = assignmentRepository.existsByTaskId(task.getId());

        if (hasAssignment && requestedStatus == TaskStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This task is assigned to a staff member, so it cannot be set back to Pending. "
                            + "Keep it Assigned or In Progress, or mark it Completed or Cancelled.");
        }

        if (!hasAssignment && requestedStatus != TaskStatus.PENDING && requestedStatus != TaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "This task has no assigned staff member, so it cannot be set to %s. "
                            + "Assign it from the Assignments page first.",
                    requestedStatus.getDisplayName()));
        }
    }

    /** What Staff may do to a task assigned to them: the next step only. Cancelling, going back, skipping a step and reopening are not theirs to do. */
    static final java.util.Map<TaskStatus, TaskStatus> STAFF_NEXT_STATUS = java.util.Map.of(
            TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS,
            TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED);

    /**
     * A Staff member moves a task assigned to THEM one step forward. Checked here, not only in the URL rules:
     * <ul>
     *   <li>the caller must be Staff (scope OWN): Admin and Manager use the full update; no sign-in is 401;</li>
     *   <li>the caller's own linked, active Staff record is found from the signed-in user name, never from the request;</li>
     *   <li>the task is locked, then it must have an assignment to THAT Staff record. A task that does not exist, one with no assignment
     *       and one assigned to somebody else all give the same 404, so nothing is revealed about other people's tasks;</li>
     *   <li>only {@link #STAFF_NEXT_STATUS} is allowed (409 otherwise). Only the status changes: no other field and no assignment.</li>
     * </ul>
     */
    @Override
    @Transactional
    public TaskDto updateOwnTaskStatus(UUID id, TaskStatus requested) {
        DepartmentScope scope = scopeResolver.resolve();
        if (!scope.isOwn()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Staff members update the status of their own tasks here. Use the task edit instead.");
        }
        if (requested == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required");
        }
        String username = scopeResolver.currentUsername();
        Staff staff = staffRepository.findByUserUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff record not found"));
        if (!staff.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your staff record is not active");
        }

        // Same order as every other task change: the task is locked first, then what it needs is read
        taskRepository.lockByIds(Set.of(id));
        Task task = taskRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        boolean mine = assignmentRepository.findAllByTaskId(id).stream()
                .anyMatch(a -> a.getStaff() != null && staff.getId().equals(a.getStaff().getId()));
        if (!mine) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }

        TaskStatus current = task.getStatus();
        if (current != null && current.isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "This task is %s and cannot be changed to %s. Finished tasks cannot be reopened.", current.getDisplayName(), requested.getDisplayName()));
        }
        TaskStatus allowed = current == null ? null : STAFF_NEXT_STATUS.get(current);
        if (allowed != requested) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, allowed == null
                    ? String.format("A task that is %s cannot be changed to %s by Staff.", current == null ? "not started" : current.getDisplayName(), requested.getDisplayName())
                    : String.format("A task that is %s can only be moved to %s, not %s.", current.getDisplayName(), allowed.getDisplayName(), requested.getDisplayName()));
        }

        task.setStatus(requested);
        task = taskRepository.saveAndFlush(task);
        // The same follow-up as the full update: temporary cross-department support ends when its task is completed
        supportLifecycle.onTaskChanged(task);
        return mapToDto(task);
    }

    @Override
    @Transactional
    public void deleteTask(UUID id) {
        DepartmentScope scope = requireTaskManagementScope();
        Task task = lockTask(scope, id); // see updateTask
        supportLifecycle.onTaskDeleted(id);
        List<Assignment> assignments = assignmentRepository.findAllByTaskId(id);
        if (!assignments.isEmpty()) {
            assignmentRepository.deleteAll(assignments);
            assignmentRepository.flush();
        }
        taskRepository.delete(task);
        taskRepository.flush();
    }

    private static boolean isCurrentDepartment(Task task, String requestedCode) {
        return task.getDepartment() != null && requestedCode != null && requestedCode.trim().equals(task.getDepartment().getCode());
    }

    private TaskDto mapToDto(Task task) {
        TaskDto dto = new TaskDto();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setCategory(task.getCategory());
        dto.setPriority(task.getPriority());
        dto.setStatus(task.getStatus());
        dto.setEstimatedDuration(task.getEstimatedDuration());
        dto.setDueDate(task.getDueDate());
        dto.setDeadlineTime(task.getDeadlineTime());
        if (task.getDepartment() != null) {
            dto.setDepartment(task.getDepartment().getCode());
            dto.setDepartmentName(task.getDepartment().getName());
        }
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        return dto;
    }
}
