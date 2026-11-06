package com.hotel.workload.assignment;

import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AssignmentServiceImpl implements AssignmentService {

    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final AssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final StaffRepository staffRepository;
    private final com.hotel.workload.support.SupportLifecycle supportLifecycle;
    private final CurrentUserScopeResolver scopeResolver;

    public AssignmentServiceImpl(AssignmentRepository assignmentRepository,
                                 TaskRepository taskRepository,
                                 StaffRepository staffRepository,
                                 com.hotel.workload.support.SupportLifecycle supportLifecycle,
                                 CurrentUserScopeResolver scopeResolver) {
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
        this.staffRepository = staffRepository;
        this.supportLifecycle = supportLifecycle;
        this.scopeResolver = scopeResolver;
    }

    /**
     * The scope for creating or reassigning: ALL (Admin) or one department (a valid Manager). Anyone else (Staff) is refused with 403
     * before any repository is read; a refused Manager or a missing sign-in is refused by the resolver (403, 401).
     */
    private DepartmentScope requireAssignmentManagementScope() {
        DepartmentScope scope = scopeResolver.resolve();
        if (!scope.isAll() && !scope.isDepartment()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
        }
        return scope;
    }

    @Override
    @Transactional
    public AssignmentDto createAssignment(CreateAssignmentRequest request) {
        DepartmentScope scope = requireAssignmentManagementScope();

        // Serialize with every other flow that touches this task or this staff member (support confirm, reassign, deactivation),
        // always task first and staff second, then check the rules on what is committed now rather than on a stale read.
        Task task;
        Staff staff;
        if (scope.isDepartment()) {
            // A Manager reaches only their department's task and staff. Anything else (other department, task with no department,
            // nonexistent) gives the same 404 and is never locked; the staff lock is taken only once the task is known to be in scope.
            task = taskRepository.lockByIdAndDepartment(request.getTaskId(), scope.department())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            staff = staffRepository.lockByIdAndDepartment(request.getStaffId(), scope.department())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));
        } else {
            taskRepository.lockByIds(Set.of(request.getTaskId()));
            staffRepository.lockByIds(Set.of(request.getStaffId()));

            task = taskRepository.findById(request.getTaskId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

            staff = staffRepository.findById(request.getStaffId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));
        }

        if (!staff.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member is not active");
        }

        if (staff.isPrivilegedAccount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tasks cannot be assigned to a manager or administrator account");
        }

        if (staff.getAvailability() != Availability.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member is not available");
        }

        if (task.getDepartment() != null && staff.getDepartment() != null && !task.getDepartment().equals(staff.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member does not belong to the task's department");
        }

        if (task.getStatus() == TaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot assign a completed task");
        }

        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot assign a cancelled task");
        }

        if (assignmentRepository.existsByTaskId(task.getId()) || task.getStatus() == TaskStatus.ASSIGNED || task.getStatus() == TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task already has an active assignment");
        }

        if (assignmentRepository.existsByStaffIdAndTaskStatusIn(staff.getId(), ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Staff member already has an active assignment");
        }

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignment = assignmentRepository.save(assignment);

        task.setStatus(TaskStatus.ASSIGNED);
        taskRepository.save(task);

        return AssignmentDto.fromEntity(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentDto> getAllAssignments() {
        DepartmentScope scope = scopeResolver.resolve();

        if (scope.isAll()) {
            return assignmentRepository.findAllByOrderByAssignedAtDesc().stream()
                    .map(AssignmentDto::fromEntity)
                    .collect(Collectors.toList());
        }
        if (scope.isDepartment()) {
            // Database-side filter on the task's department: other departments' assignments are never loaded
            return assignmentRepository.findByTaskDepartmentOrderByAssignedAtDesc(scope.department()).stream()
                    .map(AssignmentDto::fromEntity)
                    .collect(Collectors.toList());
        }

        // Staff: only their own assignments, as before
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        return staffRepository.findByUserUsername(username)
                .map(staff -> assignmentRepository.findByStaffIdOrderByAssignedAtDesc(staff.getId()).stream()
                        .map(AssignmentDto::fromEntity)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    @Override
    @Transactional
    public AssignmentDto reassignTask(UUID assignmentId, UpdateAssignmentRequest request) {
        DepartmentScope scope = requireAssignmentManagementScope();

        // Same lock order as createAssignment: the task, then the target staff member. The assignment itself is read only after
        // the locks, so a request that waited sees what the winner committed.
        Assignment assignment;
        Staff targetStaff;
        if (scope.isDepartment()) {
            // Scope is decided by the task's department and the NEW holder's department, never by the current holder (who may be lent
            // from another department by an approved support). A task outside the department, a task with no department and a
            // nonexistent assignment all give the same 404 and lock nothing.
            Optional<UUID> taskId = assignmentRepository.findTaskIdById(assignmentId);
            if (taskId.isEmpty() || taskRepository.lockByIdAndDepartment(taskId.get(), scope.department()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found");
            }
            targetStaff = staffRepository.lockByIdAndDepartment(request.getStaffId(), scope.department())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));
            assignment = assignmentRepository.findById(assignmentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));
        } else {
            assignmentRepository.findTaskIdById(assignmentId).ifPresent(taskId -> taskRepository.lockByIds(Set.of(taskId)));
            staffRepository.lockByIds(Set.of(request.getStaffId()));

            assignment = assignmentRepository.findById(assignmentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));

            targetStaff = staffRepository.findById(request.getStaffId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));
        }

        if (!targetStaff.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target staff member is not active");
        }

        if (targetStaff.isPrivilegedAccount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tasks cannot be assigned to a manager or administrator account");
        }

        if (targetStaff.getAvailability() != Availability.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target staff member is not available");
        }

        Task task = assignment.getTask();

        if (task != null && task.getDepartment() != null && targetStaff.getDepartment() != null && !task.getDepartment().equals(targetStaff.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target staff member does not belong to the task's department");
        }

        if (task != null && task.getStatus() == TaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot reassign a completed task");
        }

        if (task != null && task.getStatus() == TaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot reassign a cancelled task");
        }

        if (assignment.getStaff() != null && assignment.getStaff().getId().equals(targetStaff.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target staff member already has an active assignment for this task");
        }

        if (assignmentRepository.existsByStaffIdAndIdNotAndTaskStatusIn(targetStaff.getId(), assignment.getId(), ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target staff member already has an active assignment");
        }

        assignment.setStaff(targetStaff);
        assignment.setAssignedAt(LocalDateTime.now());
        assignment = assignmentRepository.saveAndFlush(assignment);
        // If this task was being supported from another department, that support ends: someone else holds it now
        if (task != null) supportLifecycle.onTaskReassigned(task.getId(), targetStaff.getId());

        return AssignmentDto.fromEntity(assignment);
    }
}
