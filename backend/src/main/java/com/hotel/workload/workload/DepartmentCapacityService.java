package com.hotel.workload.workload;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.Role;
import com.hotel.workload.settings.SystemSettingsDto;
import com.hotel.workload.settings.SystemSettingsService;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportAssignment;
import com.hotel.workload.support.SupportAssignmentRepository;
import com.hotel.workload.support.SupportStatus;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Computes every department's remaining workload and staff capacity (see {@link DepartmentCapacity}) from the database, once, for everyone
 * who needs it: the Workload page, the dashboard alerts and the Cross-Department Support rules all read these same figures, so "department
 * risk" can never mean two things.
 *
 * <p>Only aggregates are read (counts and sums per department code), plus the few staff records of people currently on support. Nothing here
 * decides who may see what: {@link #figures()} is internal, and {@link #forCaller()} applies the caller's scope (an Admin sees every
 * department, a valid Manager only the departments they manage, anyone else is refused).
 *
 * <p>Support moves capacity, never work: while a person is on ACTIVE support their capacity counts for the department they help and not for
 * their home department (and only while they are an eligible, available STAFF member), and the task they took stays in the receiving
 * department's remaining minutes (it moves from "waiting" to "assigned", so the total does not change).
 */
@Service
public class DepartmentCapacityService {

    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    /** One department's inputs to the capacity rule. */
    public record Figures(String code, String name, int assignedMinutes, int waitingMinutes, int activeTasks, int waitingTasks,
                          int eligibleStaff, int helpersIn, int lentOut) {
        public int remainingMinutes() { return assignedMinutes + waitingMinutes; }
    }

    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final AssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final SupportAssignmentRepository supportRepository;
    private final SystemSettingsService systemSettings;
    private final CurrentUserScopeResolver scopeResolver;

    public DepartmentCapacityService(DepartmentRepository departmentRepository, StaffRepository staffRepository, AssignmentRepository assignmentRepository,
                                     TaskRepository taskRepository, SupportAssignmentRepository supportRepository, SystemSettingsService systemSettings,
                                     CurrentUserScopeResolver scopeResolver) {
        this.departmentRepository = departmentRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
        this.supportRepository = supportRepository;
        this.systemSettings = systemSettings;
        this.scopeResolver = scopeResolver;
    }

    /** The saved Max Task Capacity Alert switch and Capacity Threshold (the defaults while nothing has been saved). Internal. */
    public DepartmentCapacity.Risk risk() {
        SystemSettingsDto settings = systemSettings.current();
        return DepartmentCapacity.Risk.of(settings.capacityAlertEnabled(), settings.capacityThresholdPercent());
    }

    /** Every active department's figures, in creation order. Internal: no permission decision is made here. */
    @Transactional(readOnly = true)
    public Map<String, Figures> figures() {
        List<Department> departments = new ArrayList<>();
        for (Department department : departmentRepository.findAllByOrderByCreatedAtAscNameAsc()) {
            if (department.isActive()) departments.add(department);
        }
        Set<String> active = new HashSet<>();
        for (Department department : departments) active.add(department.getCode());

        Map<String, Integer> homeStaff = new HashMap<>();
        for (Object[] row : staffRepository.countCapacityStaffByDepartmentCode()) homeStaff.put((String) row[0], ((Number) row[1]).intValue());

        // People on temporary support: their capacity moves from their home department to the department they help (once each)
        Map<String, Integer> lentOut = new HashMap<>();
        Map<String, Integer> helpersIn = new HashMap<>();
        Map<UUID, SupportAssignment> supportByStaff = new LinkedHashMap<>();
        for (SupportAssignment support : supportRepository.findByStatus(SupportStatus.ACTIVE)) supportByStaff.putIfAbsent(support.getStaffId(), support);
        if (!supportByStaff.isEmpty()) {
            for (Staff member : staffRepository.findAllById(supportByStaff.keySet())) {
                SupportAssignment support = supportByStaff.get(member.getId());
                String home = member.getDepartment() == null ? null : member.getDepartment().getCode();
                String receiving = support.getReceivingDepartmentCode();
                boolean eligible = home != null && member.isActive() && member.getAvailability() == Availability.AVAILABLE
                        && member.getUser() != null && member.getUser().getRole() == Role.STAFF;
                // Only a real move counts: both departments active and different. Anything else leaves the person where they are.
                if (!eligible || home.equals(receiving) || !active.contains(home) || !active.contains(receiving)) continue;
                lentOut.merge(home, 1, Integer::sum);
                helpersIn.merge(receiving, 1, Integer::sum);
            }
        }

        Map<String, int[]> assigned = new HashMap<>(); // code -> {distinct tasks, minutes}
        for (Object[] row : assignmentRepository.activeAssignedByTaskDepartmentCode(ACTIVE_STATUSES)) {
            assigned.put((String) row[0], new int[]{((Number) row[1]).intValue(), ((Number) row[2]).intValue()});
        }
        Map<String, Integer> waitingMinutes = new HashMap<>();
        for (Object[] row : taskRepository.sumWaitingMinutesByDepartmentCode(ACTIVE_STATUSES)) waitingMinutes.put((String) row[0], ((Number) row[1]).intValue());
        Map<String, Integer> waitingTasks = new HashMap<>();
        for (Object[] row : taskRepository.countWaitingByDepartmentCode(ACTIVE_STATUSES)) waitingTasks.put((String) row[0], ((Number) row[1]).intValue());

        Map<String, Figures> result = new LinkedHashMap<>();
        for (Department department : departments) {
            String code = department.getCode();
            int[] held = assigned.getOrDefault(code, new int[]{0, 0});
            int waiting = waitingTasks.getOrDefault(code, 0);
            int lent = lentOut.getOrDefault(code, 0);
            int helpers = helpersIn.getOrDefault(code, 0);
            int staff = Math.max(0, homeStaff.getOrDefault(code, 0) - lent) + helpers;
            result.put(code, new Figures(code, department.getName(), held[1], waitingMinutes.getOrDefault(code, 0), held[0] + waiting, waiting, staff, helpers, lent));
        }
        return result;
    }

    /** One department's entry under the given rule. */
    public static DepartmentCapacityDto.Entry entry(Figures figures, DepartmentCapacity.Risk risk) {
        int remaining = figures.remainingMinutes();
        int staff = figures.eligibleStaff();
        WorkloadStatus level = DepartmentCapacity.level(remaining, staff, risk);
        return new DepartmentCapacityDto.Entry(figures.code(), figures.name(), remaining, figures.assignedMinutes(), figures.waitingMinutes(),
                figures.activeTasks(), figures.waitingTasks(), staff, figures.helpersIn(), figures.lentOut(), DepartmentCapacity.capacityMinutes(staff),
                DepartmentCapacity.utilizationPercent(remaining, staff), level, level == WorkloadStatus.AT_RISK, DepartmentCapacity.noCapacity(remaining, staff));
    }

    /**
     * The capacity of the departments the caller may see: every department for an Admin, the departments they manage for a valid Manager. Staff
     * are refused (403), an invalid Manager is refused by the resolver (403) and no sign-in is 401: no department-level figure reaches anyone
     * else, however this is reached.
     */
    @Transactional(readOnly = true)
    public DepartmentCapacityDto forCaller() {
        DepartmentScope scope = scopeResolver.resolve();
        if (!scope.isAll() && !scope.isDepartment()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        DepartmentCapacity.Risk risk = risk();
        Set<String> allowed = scope.isAll() ? null : new HashSet<>(scope.managedDepartmentCodes());
        List<DepartmentCapacityDto.Entry> entries = new ArrayList<>();
        for (Figures figures : figures().values()) {
            if (allowed == null || allowed.contains(figures.code())) entries.add(entry(figures, risk));
        }
        return new DepartmentCapacityDto(risk.enabled(), risk.thresholdPercent(), DepartmentCapacity.STANDARD_SHIFT_MINUTES, entries);
    }
}
