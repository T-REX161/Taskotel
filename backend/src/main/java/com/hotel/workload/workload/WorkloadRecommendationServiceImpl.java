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

    static final String TURNED_OFF_NOTE = "Workload recommendations are turned off in System Settings (Auto-Balancing Trigger). No suggestions are being made.";

    @Override
    @Transactional(readOnly = true)
    public WorkloadRecommendationsDto getRecommendations() {
        DepartmentScope scope = scopeResolver.resolve();
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
                    LocalDateTime.now());
        }
        if (scope.isDepartment()) {
            Map<String, Integer> waitingByDepartment = new HashMap<>();
            for (Object[] row : taskRepository.countWaitingByDepartmentCode(ACTIVE_STATUSES)) {
                waitingByDepartment.put((String) row[0], ((Number) row[1]).intValue());
            }
            return WorkloadRecommendationEngine.recommendForReceiver(
                    scope.department(),
                    departmentRepository.findAllByOrderByCreatedAtAscNameAsc(),
                    staffRepository.findAllWithUser(),
                    taskRepository.findWaitingByDepartment(scope.department(), ACTIVE_STATUSES),
                    waitingByDepartment,
                    new HashSet<>(assignmentRepository.findBusyStaffIds(ACTIVE_STATUSES)),
                    LocalDate.now(),
                    LocalDateTime.now());
        }
        // Staff have no recommendations (also blocked by SecurityConfig); a direct call is refused the same way
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }
}
