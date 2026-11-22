package com.hotel.workload.dashboard;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentDto;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.ScopedWorkloadCalculator;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The dashboard summary for the signed-in user. The scope is resolved once from the database ({@link CurrentUserScopeResolver}), never
 * from role strings alone: Admin sees every department, a valid Manager sees only their own department (an invalid Manager is refused
 * with 403 for the whole dashboard, no sign-in with 401), and Staff see only their own data.
 *
 * A Manager's workload figures come from {@link ScopedWorkloadCalculator} under the SAME resolved scope (one scope snapshot per request), the same calculation as GET /api/workload,
 * so the two always agree (support-loan work and tasks with no department are excluded for a Manager, see RBAC specification 12.1).
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final StaffRepository staffRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final ScopedWorkloadCalculator workloadCalculator;
    private final CurrentUserScopeResolver scopeResolver;

    public DashboardServiceImpl(StaffRepository staffRepository,
                                TaskRepository taskRepository,
                                AssignmentRepository assignmentRepository,
                                ScopedWorkloadCalculator workloadCalculator,
                                CurrentUserScopeResolver scopeResolver) {
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.workloadCalculator = workloadCalculator;
        this.scopeResolver = scopeResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public Object getDashboardSummary(Authentication authentication) {
        DepartmentScope scope = scopeResolver.resolve();
        if (scope.isAll()) {
            return buildAdminDashboardSummary(scope);
        }
        if (scope.isDepartment()) {
            return buildDepartmentDashboardSummary(scope);
        }
        // The user name comes from the signed-in context (the same one the scope was resolved from), never from a caller-supplied value
        return buildStaffDashboardSummary(scope, SecurityContextHolder.getContext().getAuthentication().getName());
    }

    /** Admin: every department, unchanged. */
    private AdminDashboardSummaryDto buildAdminDashboardSummary(DepartmentScope scope) {
        AdminDashboardSummaryDto dto = new AdminDashboardSummaryDto();

        // Staff metrics
        dto.setTotalStaff(staffRepository.countOperational());
        dto.setAvailableStaff(staffRepository.countOperationalActiveByAvailability(Availability.AVAILABLE));
        dto.setUnavailableStaff(staffRepository.countOperationalActiveByAvailability(Availability.UNAVAILABLE));
        dto.setInactiveStaff(staffRepository.countOperationalInactive());

        // Task metrics
        dto.setTotalTasks(taskRepository.count());
        dto.setPendingTasks(taskRepository.countByStatus(TaskStatus.PENDING));
        dto.setAssignedTasks(taskRepository.countByStatus(TaskStatus.ASSIGNED));
        dto.setInProgressTasks(taskRepository.countByStatus(TaskStatus.IN_PROGRESS));
        dto.setCompletedTasks(taskRepository.countByStatus(TaskStatus.COMPLETED));
        dto.setCancelledTasks(taskRepository.countByStatus(TaskStatus.CANCELLED));

        // Assignment metrics
        dto.setTotalActiveAssignments(assignmentRepository.countActiveAssignments(ACTIVE_STATUSES));

        applyWorkloadBuckets(dto, workloadCalculator.calculateWorkload(scope));
        applyCapacityAlerts(dto, scope);
        return dto;
    }

    /**
     * Manager: only the Manager's own department. Every count is a database query filtered by the department; nothing global is loaded
     * and filtered afterwards. Tasks with no department never match, and active assignments are the same rows a Manager's workload counts
     * (task and holder both in the department), so support-loan work is excluded in both directions.
     */
    private AdminDashboardSummaryDto buildDepartmentDashboardSummary(DepartmentScope scope) {
        AdminDashboardSummaryDto dto = new AdminDashboardSummaryDto();
        // The workload rows of every managed department, calculated once and then split by department
        List<WorkloadDto> workloads = workloadCalculator.calculateWorkload(scope);
        java.util.Map<String, List<WorkloadDto>> rowsByDepartment = new java.util.HashMap<>();
        for (WorkloadDto row : workloads) rowsByDepartment.computeIfAbsent(row.getDepartment(), code -> new java.util.ArrayList<>()).add(row);

        // The figures of each managed department (the same per-department queries as before), primary first and then the additional ones
        // by name. The response totals are the sums of the entries: each staff member, task and counted assignment belongs to exactly one
        // department, so nothing is counted twice.
        List<Department> managed = new java.util.ArrayList<>(scope.managedDepartments());
        Department primary = scope.primaryDepartment();
        managed.sort(java.util.Comparator.comparing((Department d) -> !d.getCode().equals(primary.getCode()))
                .thenComparing(d -> String.valueOf(d.getName()).toLowerCase()));
        List<AdminDashboardSummaryDto.DepartmentSummary> breakdown = new java.util.ArrayList<>();
        for (Department department : managed) {
            long[] buckets = workloadBuckets(rowsByDepartment.getOrDefault(department.getCode(), List.of()));
            breakdown.add(new AdminDashboardSummaryDto.DepartmentSummary(department.getCode(), department.getName(),
                    department.getCode().equals(primary.getCode()),
                    staffRepository.countOperationalInDepartment(department),
                    staffRepository.countOperationalActiveByAvailabilityInDepartment(department, Availability.AVAILABLE),
                    staffRepository.countOperationalActiveByAvailabilityInDepartment(department, Availability.UNAVAILABLE),
                    staffRepository.countOperationalInactiveInDepartment(department),
                    taskRepository.countByDepartment(department),
                    taskRepository.countByDepartmentAndStatus(department, TaskStatus.PENDING),
                    taskRepository.countByDepartmentAndStatus(department, TaskStatus.ASSIGNED),
                    taskRepository.countByDepartmentAndStatus(department, TaskStatus.IN_PROGRESS),
                    taskRepository.countByDepartmentAndStatus(department, TaskStatus.COMPLETED),
                    taskRepository.countByDepartmentAndStatus(department, TaskStatus.CANCELLED),
                    assignmentRepository.countActiveByTaskAndStaffDepartment(ACTIVE_STATUSES, department),
                    buckets[0], buckets[1], buckets[2], buckets[3], buckets[4]));
        }
        dto.setDepartmentBreakdown(breakdown);

        dto.setTotalStaff(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::totalStaff).sum());
        dto.setAvailableStaff(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::availableStaff).sum());
        dto.setUnavailableStaff(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::unavailableStaff).sum());
        dto.setInactiveStaff(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::inactiveStaff).sum());

        dto.setTotalTasks(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::totalTasks).sum());
        dto.setPendingTasks(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::pendingTasks).sum());
        dto.setAssignedTasks(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::assignedTasks).sum());
        dto.setInProgressTasks(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::inProgressTasks).sum());
        dto.setCompletedTasks(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::completedTasks).sum());
        dto.setCancelledTasks(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::cancelledTasks).sum());

        dto.setTotalActiveAssignments(breakdown.stream().mapToLong(AdminDashboardSummaryDto.DepartmentSummary::totalActiveAssignments).sum());

        applyWorkloadBuckets(dto, workloads);
        applyCapacityAlerts(dto, scope);
        return dto;
    }

    /** The shared department capacity figures. Optional (setter) so a hand-built service in a unit test shows no alerts. */
    private com.hotel.workload.workload.DepartmentCapacityService capacityService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDepartmentCapacityService(com.hotel.workload.workload.DepartmentCapacityService capacityService) {
        this.capacityService = capacityService;
    }

    /**
     * Capacity warnings for the caller's scope, only while the Max Task Capacity Alert is on. A department is warned about when it is AT RISK:
     * its utilization (remaining task minutes / staff capacity x 100) is at or above the saved Capacity Threshold. Remaining minutes are the
     * estimated minutes of its active tasks (PENDING, ASSIGNED, IN_PROGRESS), held or waiting; capacity is its eligible staff (active STAFF
     * accounts marked AVAILABLE, a helper on support counting for the department they help) times the standard shift. A department with work
     * but nobody eligible to do it has no capacity and is always warned about. One entry per department, so there are no duplicate warnings.
     * This is the same figure and rule as the Workload page cards and Cross-Department Support (DepartmentCapacityService). A Manager's scope is
     * their own departments only. Read-only: nothing is stored, sent or reassigned.
     */
    private void applyCapacityAlerts(AdminDashboardSummaryDto dto, DepartmentScope scope) {
        if (capacityService == null) return;
        com.hotel.workload.workload.DepartmentCapacity.Risk risk = capacityService.risk();
        if (!risk.enabled()) return;
        java.util.Set<String> allowed = scope.isDepartment() ? new java.util.HashSet<>(scope.managedDepartmentCodes()) : null;

        List<AdminDashboardSummaryDto.CapacityAlert> alerts = new java.util.ArrayList<>();
        for (com.hotel.workload.workload.DepartmentCapacityService.Figures figures : capacityService.figures().values()) {
            if (allowed != null && !allowed.contains(figures.code())) continue;
            int remaining = figures.remainingMinutes();
            int staff = figures.eligibleStaff();
            if (!com.hotel.workload.workload.DepartmentCapacity.atRisk(remaining, staff, risk)) continue;
            alerts.add(new AdminDashboardSummaryDto.CapacityAlert(figures.code(), figures.name(), remaining,
                    com.hotel.workload.workload.DepartmentCapacity.capacityMinutes(staff), staff,
                    com.hotel.workload.workload.DepartmentCapacity.utilizationPercent(remaining, staff), risk.thresholdPercent(),
                    com.hotel.workload.workload.DepartmentCapacity.noCapacity(remaining, staff)));
        }
        // The most pressed first (a department with no capacity at all is the most pressed), then by name
        alerts.sort(java.util.Comparator.comparingDouble((AdminDashboardSummaryDto.CapacityAlert a) -> a.utilizationPercent() == null ? Double.MAX_VALUE : a.utilizationPercent()).reversed()
                .thenComparing(AdminDashboardSummaryDto.CapacityAlert::departmentName));
        dto.setCapacityAlerts(alerts);
    }

    /** Workload buckets from the caller's scoped workload rows (the same calculation as GET /api/workload). */
    private void applyWorkloadBuckets(AdminDashboardSummaryDto dto, List<WorkloadDto> workloads) {
        long[] buckets = workloadBuckets(workloads);
        dto.setTotalStaffWithWorkload(buckets[0]);
        dto.setLowWorkloadStaff(buckets[1]);
        dto.setNormalWorkloadStaff(buckets[2]);
        dto.setHighWorkloadStaff(buckets[3]);
        dto.setAtRiskStaff(buckets[4]);
    }

    /** {staff with workload, low, normal, high, at risk} for these rows: the one definition of the dashboard's workload buckets. */
    private static long[] workloadBuckets(List<WorkloadDto> workloads) {
        long staffWithWorkload = 0;
        long low = 0;
        long normal = 0;
        long high = 0;
        long atRisk = 0;

        for (WorkloadDto wl : workloads) {
            if (wl.getActiveTaskCount() > 0) {
                staffWithWorkload++;
            }
            switch (wl.getWorkloadStatus()) {
                case LOW: low++; break;
                case NORMAL: normal++; break;
                case HIGH: high++; break;
                case AT_RISK: atRisk++; break;
            }
        }
        return new long[]{staffWithWorkload, low, normal, high, atRisk};
    }

    /** Staff: their own data only (the username comes from the authentication, never from a request parameter). */
    private StaffDashboardSummaryDto buildStaffDashboardSummary(DepartmentScope scope, String username) {
        Staff staff = staffRepository.findByUserUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff record not found"));

        // For a Staff caller the scoped workload is their own row
        List<WorkloadDto> workloads = workloadCalculator.calculateWorkload(scope);
        WorkloadDto workload = workloads.isEmpty() ? null : workloads.get(0);

        List<Assignment> assignments = assignmentRepository.findByStaffId(staff.getId());
        List<AssignmentDto> recentAssignments = assignments.stream()
                .map(AssignmentDto::fromEntity)
                .collect(Collectors.toList());

        long pending = 0;
        long assigned = 0;
        long inProgress = 0;
        long completed = 0;

        for (Assignment a : assignments) {
            if (a.getTask() != null) {
                switch (a.getTask().getStatus()) {
                    case PENDING: pending++; break;
                    case ASSIGNED: assigned++; break;
                    case IN_PROGRESS: inProgress++; break;
                    case COMPLETED: completed++; break;
                    default: break;
                }
            }
        }

        StaffDashboardSummaryDto dto = new StaffDashboardSummaryDto();
        dto.setWorkload(workload);
        dto.setRecentAssignments(recentAssignments);
        dto.setPendingTasks(pending);
        dto.setAssignedTasks(assigned);
        dto.setInProgressTasks(inProgress);
        dto.setCompletedTasks(completed);

        return dto;
    }
}
