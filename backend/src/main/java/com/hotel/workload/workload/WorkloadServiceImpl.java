package com.hotel.workload.workload;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.support.SupportAssignment;
import com.hotel.workload.support.SupportAssignmentRepository;
import com.hotel.workload.support.SupportStatus;
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
    private final SupportAssignmentRepository supportRepository;
    private final DepartmentRepository departmentRepository;

    public WorkloadServiceImpl(StaffRepository staffRepository,
                               AssignmentRepository assignmentRepository,
                               SupportAssignmentRepository supportRepository,
                               DepartmentRepository departmentRepository) {
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.supportRepository = supportRepository;
        this.departmentRepository = departmentRepository;
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

        // Staff on temporary cross-department support, and the department they are helping
        Map<UUID, SupportAssignment> supportByStaff = new HashMap<>();
        for (SupportAssignment support : supportRepository.findByStatus(SupportStatus.ACTIVE)) {
            supportByStaff.put(support.getStaffId(), support);
        }
        Map<String, String> departmentNames = new HashMap<>();
        if (!supportByStaff.isEmpty()) {
            for (Department department : departmentRepository.findAll()) departmentNames.put(department.getCode(), department.getName());
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
