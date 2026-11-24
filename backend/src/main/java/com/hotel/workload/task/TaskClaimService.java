package com.hotel.workload.task;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Staff self-service claiming: a Staff member accepts an unassigned task of their OWN department, and the first successful claim wins.
 *
 * <p>This is not a second assignment model. A claim writes the same {@link Assignment} row a Manager's assignment writes (the claimant is
 * the assignment's staff member, {@code assignedAt} is the claim time) and moves the task PENDING to ASSIGNED; everything afterwards
 * (start, complete, workload, reminders, reassignment, unassignment) is the existing flow. {@code assignedByUserId} stays null: accepting a
 * task yourself is not an assignment by anyone (see {@link Assignment}), and the department Manager still hears about it when the task starts
 * and when it completes.
 *
 * <p>Option A (exclusive ownership) belongs to the claim: the claim needs a task nobody holds, and the assignment it writes is marked
 * {@code selfClaimed} (migration V29). A task so marked cannot be claimed by anyone else, and nobody can be added to it by a Manager's
 * multi-staff assignment or by cross-department support (see AssignmentServiceImpl and SupportAssignmentServiceImpl); a Manager can still
 * reassign or unassign it explicitly. Assignments made by Managers and Administrators (including several staff on one task) are untouched.
 *
 * <p>Concurrency: the task row is locked first, then the claimant's staff row, then the claimant's user account row. The first two are the
 * same order as every assignment flow (so no deadlock); the user row is taken last and no flow holding a user lock ever waits for a task or
 * staff lock (role changes and password resets lock users only). Everything is re-read and re-checked under the locks (task, staff and
 * account are refreshed, so a change committed while this request waited is seen): a second claimant waits, then sees an ASSIGNED task and
 * receives 409; a staff member made unavailable, moved to another department, deactivated or no longer STAFF meanwhile cannot claim; a task
 * moved to another department meanwhile is not found. The same person claiming two tasks at once is serialised by their own staff-row
 * lock, so they cannot end up with two active tasks.
 */
@Service
public class TaskClaimService {

    static final String UNAVAILABLE_TASK_MESSAGE = "This task is no longer available. Another staff member may have accepted it first.";
    static final String HAS_ACTIVE_TASK_MESSAGE = "You already have an active task. Finish it before accepting another.";
    static final String MARKED_UNAVAILABLE_MESSAGE = "You are marked as unavailable, so you cannot accept tasks.";
    static final String INACTIVE_MESSAGE = "Your staff record is not active.";
    static final String NO_DEPARTMENT_MESSAGE = "You are not assigned to a department, so you cannot accept tasks.";

    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    private final TaskRepository taskRepository;
    private final StaffRepository staffRepository;
    private final AssignmentRepository assignmentRepository;
    private final CurrentUserScopeResolver scopeResolver;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Tracks workload changes for alert emails. Optional: a service built by hand in a unit test tracks nothing. */
    private com.hotel.workload.notification.WorkloadAlertService workloadAlerts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkloadAlertService(com.hotel.workload.notification.WorkloadAlertService workloadAlerts) {
        this.workloadAlerts = workloadAlerts;
    }

    public TaskClaimService(TaskRepository taskRepository, StaffRepository staffRepository, AssignmentRepository assignmentRepository,
                            CurrentUserScopeResolver scopeResolver, UserRepository userRepository) {
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.scopeResolver = scopeResolver;
    }

    /** The caller's own Staff record. Only a STAFF account (scope OWN) gets here; Admin and Manager have no self-service claiming (403). */
    private Staff requireClaimant() {
        DepartmentScope scope = scopeResolver.resolve();
        if (!scope.isOwn()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Staff members can accept available tasks.");
        }
        return staffRepository.findByUserUsername(scopeResolver.currentUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff record not found"));
    }

    /** Why this person cannot accept a task right now (the same eligibility as a Manager's assignment), or null when they can. */
    private String whyNotEligible(Staff staff) {
        if (!staff.isActive()) return INACTIVE_MESSAGE;
        if (staff.isPrivilegedAccount() || staff.getDepartment() == null) return NO_DEPARTMENT_MESSAGE;
        if (staff.getAvailability() != Availability.AVAILABLE) return MARKED_UNAVAILABLE_MESSAGE;
        if (assignmentRepository.existsByStaffIdAndTaskStatusIn(staff.getId(), ACTIVE_STATUSES)) return HAS_ACTIVE_TASK_MESSAGE;
        return null;
    }

    /**
     * The linked login account, locked and re-read, must still be active and still a STAFF account. The JWT filter checked both when the
     * request arrived; this closes the window between that check and the claim (a deactivation or role change committed meanwhile).
     */
    private void requireActiveStaffAccount(Staff staff) {
        UUID accountId = staff.getUser() == null ? null : staff.getUser().getId();
        User account = accountId == null ? null : userRepository.findByIdForUpdate(accountId).orElse(null);
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff record not found");
        }
        entityManager.refresh(account);
        if (!account.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, INACTIVE_MESSAGE);
        }
        if (account.getRole() != Role.STAFF) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Staff members can accept available tasks.");
        }
    }

    @Transactional(readOnly = true)
    public AvailableTasksDto availableTasks() {
        Staff staff = requireClaimant();
        String reason = whyNotEligible(staff);
        // Someone who is only busy (already holding an active task) still SEES what is open in their department, so they know what is
        // waiting; canClaim stays false and claim() still refuses them. Every other reason (inactive, no department, marked unavailable)
        // keeps the empty list: such a person is not offered work at all.
        if (reason != null && !HAS_ACTIVE_TASK_MESSAGE.equals(reason)) {
            return new AvailableTasksDto(false, reason, List.of());
        }
        // The department filter, the PENDING status and "nobody holds it" are all in the query: nothing else is ever loaded
        List<TaskDto> tasks = taskRepository.findClaimableByDepartment(staff.getDepartment(), TaskStatus.PENDING).stream()
                .map(TaskDto::fromEntity)
                .toList();
        return new AvailableTasksDto(reason == null, reason, tasks);
    }

    @Transactional
    public TaskDto claim(UUID taskId) {
        Staff staff = requireClaimant();
        if (staff.getDepartment() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NO_DEPARTMENT_MESSAGE);
        }
        // Task first, then the claimant: a task of another department (or none, or a missing id) is never locked and is the same 404
        Task task = taskRepository.lockByIdAndDepartment(taskId, staff.getDepartment())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        staffRepository.lockByIds(Set.of(staff.getId()));
        // Everything below is judged on the rows as they are NOW, under the locks: what another request committed while this one waited
        // (the task's department or status, the person's availability, department, active flag or role) must be seen, not what was read first
        entityManager.refresh(task);
        entityManager.refresh(staff);
        if (staff.getDepartment() == null || !staff.getDepartment().equals(task.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        requireActiveStaffAccount(staff);
        String reason = whyNotEligible(staff);
        if (reason != null) {
            throw new ResponseStatusException(INACTIVE_MESSAGE.equals(reason) ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT, reason);
        }

        // The decisive check, made while holding the task lock: still waiting, still nobody on it
        if (task.getStatus() != TaskStatus.PENDING || assignmentRepository.existsByTaskId(task.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, UNAVAILABLE_TASK_MESSAGE);
        }

        if (workloadAlerts != null) workloadAlerts.track(List.of(staff.getId())); // before anything changes
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignment.setSelfClaimed(true); // Option A: from now on this task belongs to this person alone (see the class comment)
        // assignedByUserId stays null: accepting a task yourself is not an assignment by anyone. assignedAt (set on insert) is the claim time.
        assignmentRepository.save(assignment);
        task.setStatus(TaskStatus.ASSIGNED);
        taskRepository.save(task);
        try {
            assignmentRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, UNAVAILABLE_TASK_MESSAGE);
        }
        return TaskDto.fromEntity(task);
    }
}
