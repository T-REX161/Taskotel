package com.hotel.workload.dashboard;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentDto;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.WorkloadService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

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
    private final WorkloadService workloadService;

    public DashboardServiceImpl(StaffRepository staffRepository,
                                TaskRepository taskRepository,
                                AssignmentRepository assignmentRepository,
                                WorkloadService workloadService) {
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.workloadService = workloadService;
    }

    @Override
    @Transactional(readOnly = true)
    public Object getDashboardSummary(Authentication authentication) {
        boolean isAdminOrManager = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"));

        if (isAdminOrManager) {
            return buildAdminDashboardSummary();
        } else {
            return buildStaffDashboardSummary(authentication.getName());
        }
    }

    private AdminDashboardSummaryDto buildAdminDashboardSummary() {
        AdminDashboardSummaryDto dto = new AdminDashboardSummaryDto();

        // Staff metrics
        dto.setTotalStaff(staffRepository.count());
        dto.setAvailableStaff(staffRepository.countByActiveTrueAndAvailability(Availability.AVAILABLE));
        dto.setUnavailableStaff(staffRepository.countByActiveTrueAndAvailability(Availability.UNAVAILABLE));
        dto.setInactiveStaff(staffRepository.countByActiveFalse());

        // Task metrics
        dto.setTotalTasks(taskRepository.count());
        dto.setPendingTasks(taskRepository.countByStatus(TaskStatus.PENDING));
        dto.setAssignedTasks(taskRepository.countByStatus(TaskStatus.ASSIGNED));
        dto.setInProgressTasks(taskRepository.countByStatus(TaskStatus.IN_PROGRESS));
        dto.setCompletedTasks(taskRepository.countByStatus(TaskStatus.COMPLETED));
        dto.setCancelledTasks(taskRepository.countByStatus(TaskStatus.CANCELLED));

        // Assignment metrics
        dto.setTotalActiveAssignments(assignmentRepository.countActiveAssignments(ACTIVE_STATUSES));

        // Workload metrics (reuse WorkloadService)
        List<WorkloadDto> workloads = workloadService.calculateWorkload("admin", true);
        
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

        return dto;
    }

    private StaffDashboardSummaryDto buildStaffDashboardSummary(String username) {
        Staff staff = staffRepository.findByUserUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff record not found"));

        // Use existing logic for their workload
        List<WorkloadDto> workloads = workloadService.calculateWorkload(username, false);
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
