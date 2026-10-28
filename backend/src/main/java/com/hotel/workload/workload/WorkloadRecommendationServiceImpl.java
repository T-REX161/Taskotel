package com.hotel.workload.workload;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Loads the current departments, staff, active tasks and active assignments, then applies
 * {@link WorkloadRecommendationEngine}. Read-only: it never writes to the database.
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

    public WorkloadRecommendationServiceImpl(DepartmentRepository departmentRepository,
                                             StaffRepository staffRepository,
                                             TaskRepository taskRepository,
                                             AssignmentRepository assignmentRepository) {
        this.departmentRepository = departmentRepository;
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkloadRecommendationsDto getRecommendations() {
        return WorkloadRecommendationEngine.recommend(
                departmentRepository.findAllByOrderByCreatedAtAscNameAsc(),
                staffRepository.findAllWithUser(),
                taskRepository.findByStatusIn(ACTIVE_STATUSES),
                assignmentRepository.findAllActiveAssignments(ACTIVE_STATUSES),
                LocalDate.now(),
                LocalDateTime.now());
    }
}
