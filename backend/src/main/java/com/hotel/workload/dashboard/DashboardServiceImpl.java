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

        applyWorkloadBuckets(dto, scope);
        applyCapacityAlerts(dto, scope);
        return dto;
    }

    /**
     * Manager: only the Manager's own department. Every count is a database query filtered by the department; nothing global is loaded
     * and filtered afterwards. Tasks with no department never match, and active assignments are the same rows a Manager's workload counts
     * (task and holder both in the department), so support-loan work is excluded in both directions.
     */
    private AdminDashboardSummaryDto buildDepartmentDashboardSummary(DepartmentScope scope) {
        Department department = scope.department();
        AdminDashboardSummaryDto dto = new AdminDashboardSummaryDto();

        dto.setTotalStaff(staffRepository.countOperationalInDepartment(department));
        dto.setAvailableStaff(staffRepository.countOperationalActiveByAvailabilityInDepartment(department, Availability.AVAILABLE));
        dto.setUnavailableStaff(staffRepository.countOperationalActiveByAvailabilityInDepartment(department, Availability.UNAVAILABLE));
        dto.setInactiveStaff(staffRepository.countOperationalInactiveInDepartment(department));

        dto.setTotalTasks(taskRepository.countByDepartment(department));
        dto.setPendingTasks(taskRepository.countByDepartmentAndStatus(department, TaskStatus.PENDING));
        dto.setAssignedTasks(taskRepository.countByDepartmentAndStatus(department, TaskStatus.ASSIGNED));
        dto.setInProgressTasks(taskRepository.countByDepartmentAndStatus(department, TaskStatus.IN_PROGRESS));
        dto.setCompletedTasks(taskRepository.countByDepartmentAndStatus(department, TaskStatus.COMPLETED));
        dto.setCancelledTasks(taskRepository.countByDepartmentAndStatus(department, TaskStatus.CANCELLED));

        dto.setTotalActiveAssignments(assignmentRepository.countActiveByTaskAndStaffDepartment(ACTIVE_STATUSES, department));

        applyWorkloadBuckets(dto, scope);
        applyCapacityAlerts(dto, scope);
        return dto;
    }

    /** System Settings (Max Task Capacity Alert, Capacity Threshold). Optional (setter) so a hand-built service in a unit test shows no alerts. */
    private com.hotel.workload.settings.SystemSettingsService systemSettings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSystemSettings(com.hotel.workload.settings.SystemSettingsService systemSettings) {
        this.systemSettings = systemSettings;
    }

    /**
     * Capacity warnings for the caller's scope, only while the alert is switched on. A department is warned about when the share of its
     * eligible staff who are in work has reached the configured threshold:
     * (eligible staff with at least one active assignment) / (all eligible staff) x 100.
     * Eligible = Staff role AND active AND AVAILABLE AND has a department; numerator and denominator use exactly this one population, so the
     * percentage can never exceed 100. Managers, Administrators, inactive Staff, unavailable Staff (even when they hold work) and Staff with no
     * department are in neither. A department with no eligible staff has no denominator, so it never warns (no division by zero). One entry
     * per department, so there are no duplicate warnings.
     * A Manager's scope is their own department only. Read-only: nothing is stored, sent or reassigned.
     */
    private void applyCapacityAlerts(AdminDashboardSummaryDto dto, DepartmentScope scope) {
        if (systemSettings == null) return;
        com.hotel.workload.settings.SystemSettingsDto settings = systemSettings.current();
        if (!settings.capacityAlertEnabled()) return;
        int threshold = settings.capacityThresholdPercent();

        List<Staff> candidates = scope.isDepartment()
                ? staffRepository.findByDepartmentAndUserRole(scope.department(), com.hotel.workload.security.Role.STAFF)
                : staffRepository.findAllWithUser();
        java.util.Set<java.util.UUID> busy = new java.util.HashSet<>(assignmentRepository.findBusyStaffIds(ACTIVE_STATUSES));

        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>(); // code -> {eligible staff, eligible staff in work}
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (Staff staff : candidates) {
            // One eligible population for numerator and denominator: Staff role, active, AVAILABLE, with a department
            if (staff.getUser() == null || staff.getUser().getRole() != com.hotel.workload.security.Role.STAFF
                    || !staff.isActive() || staff.getAvailability() != Availability.AVAILABLE || staff.getDepartment() == null) continue;
            String code = staff.getDepartment().getCode();
            int[] c = counts.computeIfAbsent(code, k -> new int[2]);
            names.put(code, staff.getDepartment().getName());
            c[0]++;
            if (busy.contains(staff.getId())) c[1]++;
        }

        List<AdminDashboardSummaryDto.CapacityAlert> alerts = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : counts.entrySet()) {
            int total = e.getValue()[0];
            int inWork = e.getValue()[1];
            if (total > 0 && inWork * 100 >= threshold * total) {
                alerts.add(new AdminDashboardSummaryDto.CapacityAlert(e.getKey(), names.get(e.getKey()), inWork, total, inWork * 100 / total, threshold));
            }
        }
        alerts.sort(java.util.Comparator.comparingInt(AdminDashboardSummaryDto.CapacityAlert::percentInWork).reversed()
                .thenComparing(AdminDashboardSummaryDto.CapacityAlert::departmentName));
        dto.setCapacityAlerts(alerts);
    }

    /** Workload buckets from the caller's scoped workload (the same calculation as GET /api/workload). */
    private void applyWorkloadBuckets(AdminDashboardSummaryDto dto, DepartmentScope scope) {
        List<WorkloadDto> workloads = workloadCalculator.calculateWorkload(scope);

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

        dto.setTotalStaffWithWorkload(staffWithWorkload);
        dto.setLowWorkloadStaff(low);
        dto.setNormalWorkloadStaff(normal);
        dto.setHighWorkloadStaff(high);
        dto.setAtRiskStaff(atRisk);
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
