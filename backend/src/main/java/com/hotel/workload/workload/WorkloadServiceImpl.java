package com.hotel.workload.workload;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.Role;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.support.SupportAssignment;
import com.hotel.workload.support.SupportAssignmentRepository;
import com.hotel.workload.support.SupportStatus;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calculates staff workload on-the-fly from the PostgreSQL database.
 * No workload data is stored permanently; every call re-queries the database.
 */
@Service
public class WorkloadServiceImpl implements WorkloadService, ScopedWorkloadCalculator {

    /**
     * Task statuses that count as "active" for workload purposes.
     */
    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final StaffRepository staffRepository;
    private final AssignmentRepository assignmentRepository;
    private final SupportAssignmentRepository supportRepository;
    private final DepartmentRepository departmentRepository;
    private final CurrentUserScopeResolver scopeResolver;
    private final TaskRepository taskRepository;

    public WorkloadServiceImpl(StaffRepository staffRepository,
                               AssignmentRepository assignmentRepository,
                               SupportAssignmentRepository supportRepository,
                               DepartmentRepository departmentRepository,
                               CurrentUserScopeResolver scopeResolver,
                               TaskRepository taskRepository) {
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.supportRepository = supportRepository;
        this.departmentRepository = departmentRepository;
        this.scopeResolver = scopeResolver;
        this.taskRepository = taskRepository;
    }

    /**
     * The workload the signed-in user may see. The scope is resolved once, at the start, from the database ({@link CurrentUserScopeResolver}):
     * Admin sees every department; a valid Manager sees only the operational STAFF of the departments they manage and only the work of those
     * departments' tasks; Staff see their own row. An invalid Manager is refused (403) and no sign-in is refused (401) by the resolver.
     */
    @Override
    @Transactional(readOnly = true)
    public List<WorkloadDto> calculateWorkload() {
        DepartmentScope scope = scopeResolver.resolve();
        return calculateWorkload(scope);
    }

    /** The same calculation for a scope the caller already resolved from the resolver (see {@link ScopedWorkloadCalculator}); never resolves again. */
    @Override
    @Transactional(readOnly = true)
    public List<WorkloadDto> calculateWorkload(DepartmentScope scope) {
        java.util.Objects.requireNonNull(scope, "scope");
        if (scope.isAll()) {
            return buildGlobalRows(operationalStaff(), true);
        }
        if (scope.isDepartment()) {
            // The rows of every department this Manager manages, one department at a time (the same query per department)
            List<WorkloadDto> rows = new ArrayList<>();
            for (Department department : scope.managedDepartments()) {
                rows.addAll(buildDepartmentRows(department));
            }
            return rows;
        }
        // A Staff member's own row is always returned, whatever their state
        return buildGlobalRows(List.of(ownStaffRecord(SecurityContextHolder.getContext().getAuthentication().getName())), false);
    }

    /**
     * Department workload utilization: each department's ASSIGNED workload against its working capacity. Admin: every department; Manager: the
     * departments they manage (from the resolved scope, so nothing of another department is read); Staff: refused.
     *
     * <p>Assigned minutes are the existing per-assignee rule summed over the department's active assignment rows (every assignee carries the
     * task's full duration), counting only its own staff on its own tasks, so a helper on loan never adds to the receiving department. Capacity
     * is 480 minutes per eligible staff member, the Admin Dashboard's population. Unassigned active tasks are a separate backlog and are not
     * part of the utilization. The individual staff thresholds are not applied.
     */
    @Override
    @Transactional(readOnly = true)
    public List<DepartmentWorkloadDto> calculateDepartmentWorkload() {
        DepartmentScope scope = scopeResolver.resolve();
        if (scope.isOwn()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Department workload is not available to this account");
        }
        List<Department> departments = scope.isAll() ? departmentRepository.findAllByOrderByCreatedAtAscNameAsc() : new ArrayList<>(scope.managedDepartments());
        if (departments.isEmpty()) return List.of();
        List<String> codes = departments.stream().map(Department::getCode).collect(java.util.stream.Collectors.toList());

        Map<String, long[]> assigned = new HashMap<>(); // code -> {minutes, unique tasks, distinct staff}
        for (Object[] row : assignmentRepository.assignedWorkloadByDepartmentCode(codes, ACTIVE_STATUSES)) {
            assigned.put((String) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue(), ((Number) row[3]).longValue()});
        }
        Map<String, long[]> unassigned = new HashMap<>(); // code -> {tasks, minutes}
        for (Object[] row : taskRepository.activeUnassignedByDepartmentCode(codes, ACTIVE_STATUSES)) {
            unassigned.put((String) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        List<DepartmentWorkloadDto> result = new ArrayList<>();
        for (Department department : departments) {
            long[] a = assigned.getOrDefault(department.getCode(), new long[]{0, 0, 0});
            long[] u = unassigned.getOrDefault(department.getCode(), new long[]{0, 0});
            int eligible = (int) staffRepository.countOperationalActiveByAvailabilityInDepartment(department, com.hotel.workload.staff.Availability.AVAILABLE);
            result.add(new DepartmentWorkloadDto(department.getCode(), department.getName(), a[0], eligible, (int) a[1], (int) a[2], (int) u[0], u[1]));
        }
        return result;
    }

    /**
     * The staff an Admin or Manager list shows: deactivated staff are left out, as on the dashboard's staff counts, since they can take no
     * work. A deactivated member who somehow still holds active work is kept, so no held work is ever hidden (deactivation normally refuses
     * a member with active assignments).
     */
    private static List<Staff> withoutIdleDeactivated(List<Staff> staff, Map<UUID, List<Assignment>> byStaff) {
        return staff.stream().filter(member -> member.isActive() || byStaff.containsKey(member.getId())).collect(java.util.stream.Collectors.toList());
    }

    /** Managers and administrators are not operational staff, so they have no workload row. */
    private List<Staff> operationalStaff() {
        // Operational staff only: Managers and Administrators have no workload row, and neither does a Staff member with no department yet
        return staffRepository.findAll().stream().filter(staff -> !staff.isPrivilegedAccount() && staff.getDepartment() != null).collect(java.util.stream.Collectors.toList());
    }

    private Staff ownStaffRecord(String username) {
        return staffRepository.findByUserUsername(username)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Staff record not found"));
    }

    /**
     * A Manager's view, filtered in the database: the department's operational STAFF, and only the active assignments whose TASK belongs to
     * the department and whose holder's home department is the department. Work a member does for another department (support loan) and
     * work on tasks with no department are therefore not counted, and nothing about another department's tasks or support is loaded.
     */
    private List<WorkloadDto> buildDepartmentRows(Department department) {
        List<Staff> staff = staffRepository.findByDepartmentAndUserRole(department, Role.STAFF);
        List<Assignment> assignments = assignmentRepository.findActiveByTaskAndStaffDepartment(ACTIVE_STATUSES, department);
        Map<UUID, List<Assignment>> byStaff = groupByStaff(assignments);
        return buildRows(withoutIdleDeactivated(staff, byStaff), byStaff, Map.of(), Map.of());
    }

    /**
     * The existing calculation: every active assignment and every active support record, whatever their department. {@code listing} is true
     * for the Admin list (deactivated staff without work are left out) and false for a Staff member's own row.
     */
    private List<WorkloadDto> buildGlobalRows(List<Staff> targetStaff, boolean listing) {
        // Load all active assignments in a single JOIN FETCH query (no N+1).
        List<Assignment> activeAssignments = assignmentRepository.findAllActiveAssignments(ACTIVE_STATUSES);
        Map<UUID, List<Assignment>> byStaff = groupByStaff(activeAssignments);
        if (listing) targetStaff = withoutIdleDeactivated(targetStaff, byStaff);

        // Staff on temporary cross-department support, and the department they are helping
        Map<UUID, SupportAssignment> supportByStaff = new HashMap<>();
        for (SupportAssignment support : supportRepository.findByStatus(SupportStatus.ACTIVE)) {
            supportByStaff.put(support.getStaffId(), support);
        }
        Map<String, String> departmentNames = new HashMap<>();
        if (!supportByStaff.isEmpty()) {
            for (Department department : departmentRepository.findAll()) departmentNames.put(department.getCode(), department.getName());
        }
        return buildRows(targetStaff, byStaff, supportByStaff, departmentNames);
    }

    /** Group assignments by staff UUID for O(1) lookup. */
    private static Map<UUID, List<Assignment>> groupByStaff(List<Assignment> activeAssignments) {
        Map<UUID, List<Assignment>> byStaff = new HashMap<>();
        for (Assignment assignment : activeAssignments) {
            UUID staffId = assignment.getStaff().getId();
            byStaff.computeIfAbsent(staffId, k -> new ArrayList<>()).add(assignment);
        }
        return byStaff;
    }

    /** Build one WorkloadDto per staff member. */
    private List<WorkloadDto> buildRows(List<Staff> targetStaff, Map<UUID, List<Assignment>> byStaff,
                                        Map<UUID, SupportAssignment> supportByStaff, Map<String, String> departmentNames) {
        List<WorkloadDto> result = new ArrayList<>();
        for (Staff staff : targetStaff) {
            List<Assignment> staffAssignments = byStaff.getOrDefault(staff.getId(), List.of());

            int activeTaskCount = staffAssignments.size();
            int totalDuration = staffAssignments.stream()
                    .mapToInt(a -> {
                        Integer duration = a.getTask().getEstimatedDuration();
                        return duration != null ? duration : 0;
                    })
                    .sum();

            WorkloadDto dto = new WorkloadDto();
            dto.setStaffId(staff.getId());
            dto.setStaffName(staff.getName());
            if (staff.getDepartment() != null) {
                dto.setDepartment(staff.getDepartment().getCode());
                dto.setDepartmentName(staff.getDepartment().getName());
            }
            dto.setAvailability(staff.getAvailability());
            if (staff.getUser() != null) dto.setProfileImageUrl(staff.getUser().getProfileImageUrl());
            dto.setActiveTaskCount(activeTaskCount);
            dto.setTotalEstimatedDuration(totalDuration);
            dto.setWorkloadStatus(WorkloadDto.deriveStatus(totalDuration));
            SupportAssignment support = activeTaskCount > 0 ? supportByStaff.get(staff.getId()) : null;
            if (support != null) {
                dto.setSupportDepartment(support.getReceivingDepartmentCode());
                dto.setSupportDepartmentName(departmentNames.getOrDefault(support.getReceivingDepartmentCode(), support.getReceivingDepartmentCode()));
            }

            result.add(dto);
        }

        return result;
    }
}
