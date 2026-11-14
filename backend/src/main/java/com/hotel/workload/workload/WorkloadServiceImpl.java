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

    public WorkloadServiceImpl(StaffRepository staffRepository,
                               AssignmentRepository assignmentRepository,
                               SupportAssignmentRepository supportRepository,
                               DepartmentRepository departmentRepository,
                               CurrentUserScopeResolver scopeResolver) {
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.supportRepository = supportRepository;
        this.departmentRepository = departmentRepository;
        this.scopeResolver = scopeResolver;
    }

    /**
     * The workload the signed-in user may see. The scope is resolved once, at the start, from the database ({@link CurrentUserScopeResolver}):
     * Admin sees every department; a valid Manager sees only the operational STAFF of their own department and only the work of their own
     * department's tasks; Staff see their own row. An invalid Manager is refused (403) and no sign-in is refused (401) by the resolver.
     */
    @Override
    @Transactional(readOnly = true)
    public List<WorkloadDto> calculateWorkload() {
        return calculateWorkload(scopeResolver.resolve());
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
            return buildDepartmentRows(scope.department());
        }
        // A Staff member's own row is always returned, whatever their state
        return buildGlobalRows(List.of(ownStaffRecord(SecurityContextHolder.getContext().getAuthentication().getName())), false);
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
