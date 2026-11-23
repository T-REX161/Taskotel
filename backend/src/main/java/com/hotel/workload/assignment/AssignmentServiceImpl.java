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

    /** Tracks workload changes for alert emails. Optional: a service built by hand in a unit test tracks nothing. */
    private com.hotel.workload.notification.WorkloadAlertService workloadAlerts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkloadAlertService(com.hotel.workload.notification.WorkloadAlertService workloadAlerts) {
        this.workloadAlerts = workloadAlerts;
    }

    /** Tells assignees by email (after commit). Optional, so a service built by hand in a unit test simply sends nothing. */
    private com.hotel.workload.notification.TaskEmailNotifier taskEmails;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTaskEmailNotifier(com.hotel.workload.notification.TaskEmailNotifier taskEmails) {
        this.taskEmails = taskEmails;
    }

    /** Finds the signed-in account to record as the assigner. Optional: a service built by hand in a unit test records nobody. */
    private com.hotel.workload.security.UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUserRepository(com.hotel.workload.security.UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The signed-in account's name, or null when there is none to read (never throws: it must not be able to break an assignment). */
    private String actorUsername() {
        try {
            return scopeResolver.currentUsername();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private UUID actorId(String username) {
        if (userRepository == null || username == null) return null;
        return userRepository.findByUsername(username).map(com.hotel.workload.security.User::getId).orElse(null);
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

    /** The most staff members one request may assign (a sanity limit on how many rows are locked at once). */
    static final int MAX_STAFF_PER_REQUEST = 50;

    @Override
    @Transactional
    public AssignmentDto createAssignment(CreateAssignmentRequest request) {
        DepartmentScope scope = requireAssignmentManagementScope();
        List<UUID> staffIds = selectedStaff(request);
        if (request.getStaffIds() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send several staff members with staffIds to the multi-staff form");
        }
        return AssignmentDto.fromEntity(assign(scope, request.getTaskId(), staffIds).get(0));
    }

    @Override
    @Transactional
    public List<AssignmentDto> createAssignments(CreateAssignmentRequest request) {
        DepartmentScope scope = requireAssignmentManagementScope();
        List<UUID> staffIds = selectedStaff(request);
        if (request.getStaffIds() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "staffIds is required for a multi-staff assignment");
        }
        return assign(scope, request.getTaskId(), staffIds).stream().map(AssignmentDto::fromEntity).collect(Collectors.toList());
    }

    /**
     * The staff members a request names, in the order sent: exactly one of staffId and staffIds, and for a list: not empty, no null, no
     * duplicate, at most {@value #MAX_STAFF_PER_REQUEST}. Anything else is a 400 and nothing is read or locked.
     */
    private static List<UUID> selectedStaff(CreateAssignmentRequest request) {
        if (request == null || request.getTaskId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task ID is required");
        }
        boolean single = request.getStaffId() != null;
        boolean several = request.getStaffIds() != null;
        if (single && several) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send either staffId or staffIds, not both");
        }
        if (!single && !several) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff ID is required");
        }
        if (single) {
            return List.of(request.getStaffId());
        }
        List<UUID> ids = request.getStaffIds();
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "staffIds must contain at least one staff member");
        }
        if (ids.size() > MAX_STAFF_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "staffIds can hold at most " + MAX_STAFF_PER_REQUEST + " staff members");
        }
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "staffIds must not contain empty values");
        }
        if (new java.util.HashSet<>(ids).size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "staffIds must not contain the same staff member twice");
        }
        return ids;
    }

    /**
     * The first assignment of a task to one or more staff members, in ONE transaction: every check is made before the first row is
     * written, and any failure (the whole call is transactional) leaves nothing behind.
     *
     * <p>Locking: the task first, then ALL the selected staff in ascending id order. Every other flow (reassign, support, deactivation)
     * also locks the task before staff, and two requests that share staff lock them in the same order, so they cannot deadlock; a second
     * request for the same task waits and then sees the first one's assignments (409).
     *
     * <p>Each person is checked with the same rules as before (active, an ordinary Staff account, available, the task's department, no other
     * active assignment); the task must be unassigned and not finished. A Manager reaches only their own department's task and staff:
     * anything else is the same 404 as a record that does not exist. One refused person refuses the whole request.
     */
    private List<Assignment> assign(DepartmentScope scope, UUID taskId, List<UUID> requestedIds) {
        boolean several = requestedIds.size() > 1;
        List<UUID> locking = requestedIds.stream().sorted().toList();
        Task task;
        java.util.Map<UUID, Staff> byId = new java.util.HashMap<>();
        if (scope.isDepartment()) {
            // A Manager reaches only the task and staff of the departments they manage. Anything else (other department, task with no
            // department, nonexistent) gives the same 404 and is never locked; staff locks are taken only once the task is known to be in
            // scope. The task and each person must still be in the same department (checked below).
            task = taskRepository.lockByIdAndDepartmentIn(taskId, scope.managedDepartments())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            for (UUID id : locking) {
                byId.put(id, staffRepository.lockByIdAndDepartmentIn(id, scope.managedDepartments())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found")));
            }
        } else {
            taskRepository.lockByIds(Set.of(taskId));
            // The repository query takes the row locks in ascending id order; the set is sorted too, so the order is the same here
            staffRepository.lockByIds(new java.util.TreeSet<>(locking));

            task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
            for (UUID id : locking) {
                byId.put(id, staffRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found")));
            }
        }

        // Every person is checked before anything is written; the messages are the single-staff ones (with the name when several were sent)
        for (UUID id : locking) {
            Staff staff = byId.get(id);
            if (!staff.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, named("Staff member is not active", staff, several));
            }
            if (staff.isPrivilegedAccount()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, named("Tasks cannot be assigned to a manager or administrator account", staff, several));
            }
            if (staff.getAvailability() != Availability.AVAILABLE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, named("Staff member is not available", staff, several));
            }
            if (task.getDepartment() != null && staff.getDepartment() != null && !task.getDepartment().equals(staff.getDepartment())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, named("Staff member does not belong to the task's department", staff, several));
            }
            // An operational Staff member without a department is not an assignment target, whatever the task's department
            if (staff.getDepartment() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, named("Staff member has no department and cannot be assigned tasks", staff, several));
            }
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

        for (UUID id : locking) {
            Staff staff = byId.get(id);
            if (assignmentRepository.existsByStaffIdAndTaskStatusIn(staff.getId(), ACTIVE_STATUSES)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, named("Staff member already has an active assignment", staff, several));
            }
        }

        if (workloadAlerts != null) workloadAlerts.track(requestedIds); // workload alert emails: before anything changes
        // Everything passed: one assignment per person (returned in the order they were sent), and the task becomes ASSIGNED once
        List<Assignment> created = new java.util.ArrayList<>();
        String actorUsername = actorUsername();
        UUID assignerId = actorId(actorUsername);
        for (UUID id : requestedIds) {
            Assignment assignment = new Assignment();
            assignment.setTask(task);
            assignment.setStaff(byId.get(id));
            assignment.setAssignedByUserId(assignerId); // the original assigner, kept as history
            created.add(assignmentRepository.save(assignment));
        }
        task.setStatus(TaskStatus.ASSIGNED);
        taskRepository.save(task);
        try {
            assignmentRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The database refused a duplicate (task, staff) pair: nothing is kept
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task already has an active assignment");
        }
        // Each new assignee is told once, after this transaction commits (nothing is sent if anything above failed)
        if (taskEmails != null) taskEmails.taskAssigned(task, requestedIds.stream().map(byId::get).toList(), actorUsername);
        return created;
    }

    /** The single-staff message as it always was; when several were sent it says who the problem is about. */
    private static String named(String message, Staff staff, boolean several) {
        return several ? message + ": " + staff.getName() : message;
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
            // Database-side filter on the task's department (the departments this Manager manages): others' assignments are never loaded
            return assignmentRepository.findByTaskDepartmentInOrderByAssignedAtDesc(scope.managedDepartments()).stream()
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
            if (taskId.isEmpty() || taskRepository.lockByIdAndDepartmentIn(taskId.get(), scope.managedDepartments()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found");
            }
            targetStaff = staffRepository.lockByIdAndDepartmentIn(request.getStaffId(), scope.managedDepartments())
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

        if (targetStaff.getDepartment() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target staff member has no department and cannot be assigned tasks");
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

        Staff previousHolder = assignment.getStaff();
        if (workloadAlerts != null) {
            workloadAlerts.track(previousHolder == null ? List.of(targetStaff.getId()) : List.of(previousHolder.getId(), targetStaff.getId()));
        }
        assignment.setStaff(targetStaff);
        assignment.setAssignedAt(LocalDateTime.now());
        assignment = assignmentRepository.saveAndFlush(assignment);
        // If this task was being supported from another department, that support ends: someone else holds it now
        if (task != null) supportLifecycle.onTaskReassigned(task.getId(), targetStaff.getId());
        // The new assignee is told it is theirs and the previous one that it no longer is, after commit
        if (taskEmails != null && task != null) taskEmails.taskReassigned(task, previousHolder, targetStaff, actorUsername());

        return AssignmentDto.fromEntity(assignment);
    }

    @Override
    @Transactional
    public void unassign(UUID assignmentId) {
        DepartmentScope scope = requireAssignmentManagementScope();

        // Same lock order as reassign: the task first, then the staff member. A Manager reaches only a task of a department they manage;
        // anything else is the same 404 as an assignment that does not exist.
        Optional<UUID> taskId = assignmentRepository.findTaskIdById(assignmentId);
        if (taskId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found");
        }
        Task task;
        if (scope.isDepartment()) {
            task = taskRepository.lockByIdAndDepartmentIn(taskId.get(), scope.managedDepartments())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));
        } else {
            taskRepository.lockByIds(Set.of(taskId.get()));
            task = taskRepository.findById(taskId.get())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));
        }
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));

        if (task.getStatus() == TaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot remove staff from a completed task");
        }
        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot remove staff from a cancelled task");
        }

        Staff holder = assignment.getStaff();
        if (workloadAlerts != null && holder != null) {
            workloadAlerts.track(List.of(holder.getId())); // before anything changes
        }
        assignmentRepository.delete(assignment);
        assignmentRepository.flush();

        // Nobody left on the task: it is waiting for someone again
        if (!assignmentRepository.existsByTaskId(task.getId())) {
            task.setStatus(TaskStatus.PENDING);
            taskRepository.save(task);
        }
        // Support of this task (staff lent from another department) ends unless the supported person still holds it
        supportLifecycle.onTaskReassigned(task.getId(), null);
        if (taskEmails != null && holder != null) taskEmails.taskReassigned(task, holder, null, actorUsername());
    }
}
