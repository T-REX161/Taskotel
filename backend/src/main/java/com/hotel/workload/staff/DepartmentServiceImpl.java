package com.hotel.workload.staff;

import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DepartmentServiceImpl implements DepartmentService {

    private final StaffRepository staffRepository;
    private final TaskRepository taskRepository;

    public DepartmentServiceImpl(StaffRepository staffRepository, TaskRepository taskRepository) {
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    public List<DepartmentDto> getAllDepartments() {
        List<TaskStatus> activeStatuses = Arrays.asList(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);
        
        return Arrays.stream(Department.values()).map(dept -> {
            long staffCount = staffRepository.countByDepartmentAndActiveTrue(dept);
            long activeTasks = taskRepository.countByDepartmentAndStatusIn(dept, activeStatuses);
            
            String name = formatDepartmentName(dept);
            
            // If a task-to-staff status calculation would be misleading when staff count is zero, choose a safe status and document the rule.
            String status;
            if (staffCount == 0) {
                // Rule: Cannot calculate workload ratio when staff count is zero.
                status = "N/A (No Staff)";
            } else {
                double ratio = (double) activeTasks / staffCount;
                if (ratio < 2.0) {
                    status = "Optimal";
                } else if (ratio < 4.0) {
                    status = "Moderate";
                } else {
                    status = "High";
                }
            }

            return new DepartmentDto(
                dept.name(),
                name,
                staffCount,
                activeTasks,
                status,
                "Not Assigned"
            );
        }).collect(Collectors.toList());
    }
    
    private String formatDepartmentName(Department dept) {
        switch (dept) {
            case HOUSEKEEPING: return "Housekeeping";
            case FRONT_DESK: return "Front Desk";
            case MAINTENANCE: return "Maintenance";
            case ROOM_SERVICE: return "Room Service";
            case FOOD_AND_BEVERAGE: return "Food & Beverage";
            case SECURITY: return "Security";
            default: return dept.name();
        }
    }
}
