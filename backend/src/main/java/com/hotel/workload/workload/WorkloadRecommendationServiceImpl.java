package com.hotel.workload.workload;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Loads the data and applies {@link WorkloadRecommendationEngine}. Read-only: it never writes to the database.
 *
 * <p>The caller's scope is resolved ONCE from the database ({@link CurrentUserScopeResolver}), never from a request value:
 * an Admin gets the global recommendations; a valid Manager gets only their own department as the receiving department, with redacted
 * donor information (see {@link WorkloadRecommendationEngine#recommendForReceiver}); an invalid Manager is refused (403), no sign-in is
 * refused (401), and Staff are refused (403, also at the URL level).
 */
@Service
public class WorkloadRecommendationServiceImpl implements WorkloadRecommendationService {

    /** The same statuses that count as active work in WorkloadServiceImpl. */
    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final CurrentUserScopeResolver scopeResolver;

    public WorkloadRecommendationServiceImpl(DepartmentRepository departmentRepository,
                                             StaffRepository staffRepository,
                                             TaskRepository taskRepository,
                                             AssignmentRepository assignmentRepository,
                                             CurrentUserScopeResolver scopeResolver) {
        this.departmentRepository = departmentRepository;
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.scopeResolver = scopeResolver;
    }

    /** System Settings, Auto-Balancing Trigger. Optional (setter) so a hand-built service in a unit test keeps the default (on). */
    private com.hotel.workload.settings.SystemSettingsService systemSettings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSystemSettings(com.hotel.workload.settings.SystemSettingsService systemSettings) {
        this.systemSettings = systemSettings;
    }

    /** The shared department capacity figures. Optional (setter) so a hand-built service in a unit test keeps the original uncovered-task rule only. */
    private DepartmentCapacityService capacityService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDepartmentCapacityService(DepartmentCapacityService capacityService) {
        this.capacityService = capacityService;
    }

    /**
     * Every department's remaining task minutes and capacity staff, and the risk rule, so a department can need help because of its utilization
     * (not only because it has more waiting tasks than free staff) and a donor is never pushed past its own limit. Aggregates only.
     */
    /** For support lookups: which tasks already have a helper. Optional (setter) like the capacity service. */
    private com.hotel.workload.support.SupportAssignmentRepository supportRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSupportRepository(com.hotel.workload.support.SupportAssignmentRepository supportRepository) {
        this.supportRepository = supportRepository;
    }

    /** The tasks a helper may join, by department code: held by the department's own staff and without a helper yet. Limited to {@code receivers}. */
    private Map<String, List<com.hotel.workload.task.Task>> shareableTasks(java.util.Collection<com.hotel.workload.staff.Department> receivers) {
        Map<String, List<com.hotel.workload.task.Task>> result = new HashMap<>();
        if (supportRepository == null || receivers.isEmpty()) return result;
        List<String> codes = receivers.stream().map(com.hotel.workload.staff.Department::getCode).toList();
        java.util.Set<java.util.UUID> supported = new HashSet<>(supportRepository.findTaskIdsByStatusAndReceivingDepartmentCodes(com.hotel.workload.support.SupportStatus.ACTIVE, codes));
        for (com.hotel.workload.task.Task task : taskRepository.findHeldByDepartments(receivers, ACTIVE_STATUSES)) {
            if (task.getDepartment() == null || !WorkloadRecommendationEngine.canShare(task) || supported.contains(task.getId())) continue;
            result.computeIfAbsent(task.getDepartment().getCode(), k -> new java.util.ArrayList<>()).add(task);
        }
        return result;
    }

    private WorkloadRecommendationEngine.CapacityInputs capacityInputs(java.util.Collection<com.hotel.workload.staff.Department> receivers) {
        WorkloadRecommendationEngine.CapacityInputs inputs = capacityInputs();
        return inputs.modelled() ? inputs.withShareable(shareableTasks(receivers)) : inputs;
    }

    private WorkloadRecommendationEngine.CapacityInputs capacityInputs() {
        if (capacityService == null) return WorkloadRecommendationEngine.CapacityInputs.NONE;
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, Integer> staff = new HashMap<>();
        for (DepartmentCapacityService.Figures figures : capacityService.figures().values()) {
            remaining.put(figures.code(), figures.remainingMinutes());
            staff.put(figures.code(), figures.eligibleStaff());
        }
        return WorkloadRecommendationEngine.CapacityInputs.of(remaining, staff, capacityService.risk());
    }

    static final String CROSS_DEPARTMENT_OFF_NOTE = "Cross-department assistance is turned off in System Settings, so no support suggestions are being made.";

    static final String TURNED_OFF_NOTE ="Workload recommendations are turned off in System Settings (Auto-Balancing Trigger). No suggestions are being made.";

    @Override
    @Transactional(readOnly = true)
    public WorkloadRecommendationsDto getRecommendations() {
        DepartmentScope scope = scopeResolver.resolve();
        if ((scope.isAll() || scope.isDepartment()) && systemSettings != null && !systemSettings.current().crossDepartmentAssistanceEnabled()) {
            // These are suggestions for cross-department support: while that is switched off hotel-wide, none are made for anyone
            return new WorkloadRecommendationsDto(LocalDateTime.now(), List.of(), List.of(), List.of(CROSS_DEPARTMENT_OFF_NOTE));
        }
        if ((scope.isAll() || scope.isDepartment()) && systemSettings != null && !systemSettings.current().autoBalancingEnabled()) {
            // Suggestions only: switching this off hides them. Nothing is stored, assigned or moved either way.
            return new WorkloadRecommendationsDto(LocalDateTime.now(), List.of(), List.of(), List.of(TURNED_OFF_NOTE));
        }
        if (scope.isAll()) {
            return WorkloadRecommendationEngine.recommend(
                    departmentRepository.findAllByOrderByCreatedAtAscNameAsc(),
                    staffRepository.findAllWithUser(),
                    taskRepository.findByStatusIn(ACTIVE_STATUSES),
                    assignmentRepository.findAllActiveAssignments(ACTIVE_STATUSES),
                    LocalDate.now(),
                    LocalDateTime.now(),
                    capacityInputs(departmentRepository.findAllByOrderByCreatedAtAscNameAsc().stream().filter(com.hotel.workload.staff.Department::isActive).toList()));
        }
        if (scope.isDepartment()) {
            Map<String, Integer> waitingByDepartment = new HashMap<>();
            for (Object[] row : taskRepository.countWaitingByDepartmentCode(ACTIVE_STATUSES)) {
                waitingByDepartment.put((String) row[0], ((Number) row[1]).intValue());
            }
            // Recommendations for EVERY department this Manager manages (primary and Administrator-granted): the waiting tasks of those
            // departments only are loaded; for every other department just the number of tasks waiting there.
            Map<String, List<com.hotel.workload.task.Task>> waitingByManaged = new HashMap<>();
            for (com.hotel.workload.staff.Department managed : scope.managedDepartments()) {
                waitingByManaged.put(managed.getCode(), taskRepository.findWaitingByDepartment(managed, ACTIVE_STATUSES));
            }
            return WorkloadRecommendationEngine.recommendForManager(
                    scope.managedDepartments(),
                    departmentRepository.findAllByOrderByCreatedAtAscNameAsc(),
                    staffRepository.findAllWithUser(),
                    waitingByManaged,
                    waitingByDepartment,
                    new HashSet<>(assignmentRepository.findBusyStaffIds(ACTIVE_STATUSES)),
                    LocalDate.now(),
                    LocalDateTime.now(),
                    capacityInputs(scope.managedDepartments()));
        }
        // Staff have no recommendations (also blocked by SecurityConfig); a direct call is refused the same way
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }
}
