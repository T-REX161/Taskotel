package com.hotel.workload.staff;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.PasswordValidatorService;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StaffServiceImpl implements StaffService {

    private static final List<TaskStatus> ACTIVE_TASK_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private static final String DEACTIVATE_WITH_ACTIVE_ASSIGNMENTS_MESSAGE =
            "Cannot deactivate staff member with active task assignments. Please reassign or complete their active tasks first.";

    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordValidatorService passwordValidatorService;
    private final AssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;

    @Autowired
    public StaffServiceImpl(StaffRepository staffRepository,
                            UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            PasswordValidatorService passwordValidatorService,
                            AssignmentRepository assignmentRepository,
                            TaskRepository taskRepository) {
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidatorService = passwordValidatorService != null ? passwordValidatorService : new PasswordValidatorService();
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
    }

    public StaffServiceImpl(StaffRepository staffRepository,
                            UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            PasswordValidatorService passwordValidatorService) {
        this(staffRepository, userRepository, passwordEncoder, passwordValidatorService, null, null);
    }

    public StaffServiceImpl(StaffRepository staffRepository, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(staffRepository, userRepository, passwordEncoder, new PasswordValidatorService(), null, null);
    }

    @Override
    public List<StaffDto> getAllStaff() {
        return staffRepository.findAll().stream()
                .map(StaffDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public StaffDto createStaff(CreateStaffRequest request) {
        passwordValidatorService.validate(request.getTemporaryPassword());

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getTemporaryPassword()));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setCredentialsSetupRequired(true);
        user = userRepository.save(user);

        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName(request.getFullName());
        staff.setEmail(request.getEmail());
        staff.setPhone(request.getPhone());
        staff.setDepartment(request.getDepartment());
        staff.setAvailability(request.getAvailability());
        staff.setActive(true);
        
        staff = staffRepository.save(staff);

        return StaffDto.fromEntity(staff);
    }

    @Override
    @Transactional
    public StaffDto updateStaff(java.util.UUID id, UpdateStaffRequest request) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        // Reject before any field changes so a refused deactivation leaves the record untouched
        if (staff.isActive() && !request.isActive() && hasActiveAssignments(staff.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, DEACTIVATE_WITH_ACTIVE_ASSIGNMENTS_MESSAGE);
        }

        if (request.getDepartment() != staff.getDepartment()) {
            List<Task> conflictingTasks = activeTasksOutsideDepartment(staff.getId(), request.getDepartment());
            if (!conflictingTasks.isEmpty()) {
                String taskList = conflictingTasks.stream()
                        .map(t -> t.getTitle() + " (" + t.getDepartment().getDisplayName() + ")")
                        .collect(Collectors.joining(", "));
                throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                        "Cannot move %s to %s because they have active tasks in another department: %s. "
                                + "Reassign or complete those tasks first.",
                        staff.getName(), request.getDepartment().getDisplayName(), taskList));
            }
        }

        if (!staff.getEmail().equals(request.getEmail())) {
            if (staffRepository.existsByEmail(request.getEmail()) || userRepository.existsByEmail(request.getEmail())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
            }
        }

        staff.setName(request.getFullName());
        staff.setEmail(request.getEmail());
        staff.setPhone(request.getPhone());
        staff.setDepartment(request.getDepartment());
        staff.setAvailability(request.getAvailability());
        staff.setActive(request.isActive());

        User user = staff.getUser();
        if (user != null) {
            user.setEmail(request.getEmail());
            user.setActive(request.isActive());
        }

        staff = staffRepository.save(staff);
        return StaffDto.fromEntity(staff);
    }

    @Override
    @Transactional
    public StaffDto deactivateStaff(java.util.UUID id) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        if (!staff.isActive() && (staff.getUser() == null || !staff.getUser().isActive())) {
            return StaffDto.fromEntity(staff);
        }

        if (staff.isActive() && hasActiveAssignments(staff.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, DEACTIVATE_WITH_ACTIVE_ASSIGNMENTS_MESSAGE);
        }

        staff.setActive(false);
        User user = staff.getUser();
        if (user != null) {
            user.setActive(false);
            if (userRepository != null) {
                userRepository.save(user);
            }
        }

        staff = staffRepository.save(staff);
        return StaffDto.fromEntity(staff);
    }

    @Override
    @Transactional
    public StaffDto activateStaff(java.util.UUID id) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        if (staff.isActive() && (staff.getUser() == null || staff.getUser().isActive())) {
            return StaffDto.fromEntity(staff);
        }

        staff.setActive(true);
        User user = staff.getUser();
        if (user != null) {
            user.setActive(true);
            if (userRepository != null) {
                userRepository.save(user);
            }
        }

        staff = staffRepository.save(staff);
        return StaffDto.fromEntity(staff);
    }

    @Override
    @Transactional
    public void removeStaff(UUID id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators are authorized to remove staff members");
        }

        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));

        if (staff.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot remove an active staff member. Staff must be deactivated before removal.");
        }

        if (assignmentRepository != null) {
            if (hasActiveAssignments(staff.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove staff member with active task assignments. Please reassign or complete active tasks first.");
            }

            List<Assignment> historicalAssignments = assignmentRepository.findByStaffId(staff.getId());
            if (!historicalAssignments.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot permanently remove staff member: staff member has existing task assignment history. In accordance with data integrity rules, historical assignments cannot be deleted. Deactivation should be used instead.");
            }
        }

        User user = staff.getUser();
        staffRepository.delete(staff);
        staffRepository.flush();

        if (user != null && userRepository != null) {
            boolean hasCreatedTasks = false;
            if (taskRepository != null) {
                hasCreatedTasks = taskRepository.existsByCreatedById(user.getId());
            }
            if (!hasCreatedTasks) {
                userRepository.delete(user);
                userRepository.flush();
            }
        }
    }

    /**
     * True when the staff member holds an assignment whose task is PENDING, ASSIGNED or IN_PROGRESS.
     * Returns false when no AssignmentRepository is wired (legacy constructors).
     */
    private boolean hasActiveAssignments(UUID staffId) {
        return assignmentRepository != null
                && assignmentRepository.existsByStaffIdAndTaskStatusIn(staffId, ACTIVE_TASK_STATUSES);
    }

    /**
     * Active (PENDING, ASSIGNED, IN_PROGRESS) tasks held by the staff member whose department
     * differs from the given one. Tasks without a department never conflict.
     */
    private List<Task> activeTasksOutsideDepartment(UUID staffId, Department department) {
        if (assignmentRepository == null) {
            return List.of();
        }
        return assignmentRepository.findByStaffIdOrderByAssignedAtDesc(staffId).stream()
                .map(Assignment::getTask)
                .filter(task -> task != null && ACTIVE_TASK_STATUSES.contains(task.getStatus()))
                .filter(task -> task.getDepartment() != null && task.getDepartment() != department)
                .collect(Collectors.toList());
    }
}
