package com.hotel.workload.assignment;

import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AssignmentServiceImpl implements AssignmentService {

    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final AssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final StaffRepository staffRepository;

    public AssignmentServiceImpl(AssignmentRepository assignmentRepository,
                                 TaskRepository taskRepository,
                                 StaffRepository staffRepository) {
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
        this.staffRepository = staffRepository;
    }

    @Override
    @Transactional
    public AssignmentDto createAssignment(CreateAssignmentRequest request) {
        Task task = taskRepository.findById(request.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        Staff staff = staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        if (!staff.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member is not active");
        }

        if (staff.getAvailability() != Availability.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member is not available");
        }

        if (task.getDepartment() != null && staff.getDepartment() != null && !task.getDepartment().equals(staff.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member does not belong to the task's department");
        }

        if (task.getStatus() == TaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot assign a completed task");
        }

        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot assign a cancelled task");
        }

        if (assignmentRepository.existsByTaskId(task.getId()) || task.getStatus() == TaskStatus.ASSIGNED || task.getStatus() == TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task already has an active assignment");
        }

        if (assignmentRepository.existsByStaffIdAndTaskStatusIn(staff.getId(), ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Staff member already has an active assignment");
        }

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignment = assignmentRepository.save(assignment);

        task.setStatus(TaskStatus.ASSIGNED);
        taskRepository.save(task);

        return AssignmentDto.fromEntity(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentDto> getAllAssignments() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Collections.emptyList();
        }

        if (isAdminOrManager(auth)) {
            return assignmentRepository.findAllByOrderByAssignedAtDesc().stream()
                    .map(AssignmentDto::fromEntity)
                    .collect(Collectors.toList());
        }

        String username = auth.getName();
        if (username == null) {
            return Collections.emptyList();
        }

        return staffRepository.findByUserUsername(username)
                .map(staff -> assignmentRepository.findByStaffIdOrderByAssignedAtDesc(staff.getId()).stream()
                        .map(AssignmentDto::fromEntity)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    private boolean isAdminOrManager(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ADMIN") 
                            || a.equals("ROLE_MANAGER") || a.equals("MANAGER"));
    }

    @Override
    @Transactional
    public AssignmentDto reassignTask(UUID assignmentId, UpdateAssignmentRequest request) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));

        Staff targetStaff = staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        if (!targetStaff.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target staff member is not active");
        }

        if (targetStaff.getAvailability() != Availability.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target staff member is not available");
        }

        Task task = assignment.getTask();

        if (task != null && task.getDepartment() != null && targetStaff.getDepartment() != null && !task.getDepartment().equals(targetStaff.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target staff member does not belong to the task's department");
        }

        if (task != null && task.getStatus() == TaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot reassign a completed task");
        }

        if (task != null && task.getStatus() == TaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot reassign a cancelled task");
        }

        if (assignment.getStaff() != null && assignment.getStaff().getId().equals(targetStaff.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target staff member already has an active assignment for this task");
        }

        if (assignmentRepository.existsByStaffIdAndIdNotAndTaskStatusIn(targetStaff.getId(), assignment.getId(), ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target staff member already has an active assignment");
        }

        assignment.setStaff(targetStaff);
        assignment.setAssignedAt(LocalDateTime.now());
        assignment = assignmentRepository.saveAndFlush(assignment);

        return AssignmentDto.fromEntity(assignment);
    }
}
