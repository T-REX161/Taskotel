package com.hotel.workload.workload;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskStatus;
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
public class WorkloadServiceImpl implements WorkloadService {

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

    public WorkloadServiceImpl(StaffRepository staffRepository,
                               AssignmentRepository assignmentRepository) {
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkloadDto> calculateWorkload(String username, boolean isAdminOrManager) {
        // 1. Load staff to calculate for
        List<Staff> targetStaff = new ArrayList<>();
        if (isAdminOrManager) {
            targetStaff = staffRepository.findAll();
        } else {
            Staff staff = staffRepository.findByUserUsername(username)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Staff record not found"));
            targetStaff.add(staff);
        }

        // 2. Load all active assignments in a single JOIN FETCH query (no N+1).
        List<Assignment> activeAssignments = assignmentRepository.findAllActiveAssignments(ACTIVE_STATUSES);

        // 3. Group assignments by staff UUID for O(1) lookup.
        Map<UUID, List<Assignment>> byStaff = new HashMap<>();
        for (Assignment assignment : activeAssignments) {
            UUID staffId = assignment.getStaff().getId();
            byStaff.computeIfAbsent(staffId, k -> new ArrayList<>()).add(assignment);
        }

        // 4. Build one WorkloadDto per staff member.
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

            result.add(dto);
        }

        return result;
    }
}
