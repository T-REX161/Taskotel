package com.hotel.workload.task;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentResolver;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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

    public TaskServiceImpl(TaskRepository taskRepository,
                           UserRepository userRepository,
                           StaffRepository staffRepository,
                           AssignmentRepository assignmentRepository,
                           DepartmentResolver departmentResolver,
                           SupportLifecycle supportLifecycle) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.departmentResolver = departmentResolver;
        this.supportLifecycle = supportLifecycle;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskDto> getAllTasks() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Collections.emptyList();
        }

        if (isAdminOrManager(auth)) {
            return taskRepository.findAll().stream()
                    .map(this::mapToDto)
                    .collect(Collectors.toList());
        }

        String username = auth.getName();
        if (username == null) {
            return Collections.emptyList();
        }

        return staffRepository.findByUserUsername(username)
                .map(staff -> taskRepository.findTasksByStaffId(staff.getId()).stream()
                        .map(this::mapToDto)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    private boolean isAdminOrManager(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ADMIN") 
                            || a.equals("ROLE_MANAGER") || a.equals("MANAGER"));
    }

    @Override
    @Transactional
    public TaskDto createTask(CreateTaskRequest request) {
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
        task.setDepartment(departmentResolver.optionalActive(request.getDepartment()));
        task.setCreatedBy(createdBy);
        // createdAt / updatedAt handled by @PrePersist on the entity
        task = taskRepository.save(task);
        return mapToDto(task);
    }

    @Override
    @Transactional
    public TaskDto updateTask(UUID id, UpdateTaskRequest request) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

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

    @Override
    @Transactional
    public void deleteTask(UUID id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        supportLifecycle.onTaskDeleted(id);
        List<Assignment> assignments = assignmentRepository.findAllByTaskId(id);
        if (!assignments.isEmpty()) {
            assignmentRepository.deleteAll(assignments);
            assignmentRepository.flush();
        }
        taskRepository.delete(task);
        taskRepository.flush();
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
