package com.hotel.workload.security;

import com.hotel.workload.notification.NotificationPreferenceRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.StaffService;
import com.hotel.workload.support.SupportAssignmentRepository;
import com.hotel.workload.task.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Administrator actions on a user account: activate, deactivate, remove. They target the <em>User</em>, so an account without a staff
 * record (for example the seeded administrator) can be managed too.
 *
 * <p>An account that does have a staff record goes through {@link StaffService}, so every staff rule still applies unchanged (no
 * deactivation with active assignments, no removal with assignment history, managers' and admins' accounts only by an admin).
 * An account without one is handled here with the rules that make sense for a bare account. Nothing is deleted by cascade: a
 * removal is refused, with the reason and a pointer to deactivation, whenever other records still refer to the account.
 *
 * <p>Who may call this is decided by the security rules ({@code /api/users/**} is ADMIN only); the account acted on is the one in the
 * path, and the caller's own account is identified from the security context and never touched.
 */
@Service
public class UserAccountService {

    /** Shared with StaffServiceImpl.removeStaff: the same rule on both deletion paths. */
    public static final String ADMIN_ACCOUNT_MESSAGE = "Administrator accounts cannot be permanently removed. Deactivate the account instead.";
    static final String OWN_ACCOUNT_MESSAGE = "You cannot deactivate or remove your own account.";
    static final String LAST_ADMIN_MESSAGE = "Cannot deactivate the last active administrator.";
    static final String REMOVE_ACTIVE_MESSAGE = "Cannot remove an active account. Deactivate it first.";
    static final String HAS_TASKS_MESSAGE = "This account cannot be removed because it created tasks, and removing it would erase that history. Deactivate it instead.";
    static final String HAS_APPROVALS_MESSAGE = "This account cannot be removed because it approved support records, and removing it would erase that history. Deactivate it instead.";

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final StaffService staffService;
    private final TaskRepository taskRepository;
    private final SupportAssignmentRepository supportRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final CurrentUserScopeResolver scopeResolver;

    public UserAccountService(UserRepository userRepository, StaffRepository staffRepository, StaffService staffService,
                              TaskRepository taskRepository, SupportAssignmentRepository supportRepository,
                              NotificationPreferenceRepository preferenceRepository, CurrentUserScopeResolver scopeResolver) {
        this.scopeResolver = scopeResolver;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.staffService = staffService;
        this.taskRepository = taskRepository;
        this.supportRepository = supportRepository;
        this.preferenceRepository = preferenceRepository;
    }

    @Transactional
    public UserDto deactivate(UUID id) {
        scopeResolver.requireAdmin();
        String actingUsername = scopeResolver.currentUsername(); // from the security context, never from the caller
        User user = find(id);
        requireNotSelf(user, actingUsername);
        if (user.isActive() && user.getRole() == Role.ADMIN && userRepository.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, LAST_ADMIN_MESSAGE);
        }
        Optional<Staff> staff = staffRepository.findByUserUsername(user.getUsername());
        if (staff.isPresent()) {
            staffService.deactivateStaff(staff.get().getId()); // staff rules (e.g. active assignments) still apply
        } else if (user.isActive()) {
            userRepository.updateActive(user.getId(), false);
        }
        return reload(id);
    }

    @Transactional
    public UserDto activate(UUID id) {
        scopeResolver.requireAdmin();
        User user = find(id);
        Optional<Staff> staff = staffRepository.findByUserUsername(user.getUsername());
        if (staff.isPresent()) {
            staffService.activateStaff(staff.get().getId());
        } else if (!user.isActive()) {
            userRepository.updateActive(user.getId(), true);
        }
        return reload(id);
    }

    @Transactional
    public void remove(UUID id) {
        scopeResolver.requireAdmin();
        String actingUsername = scopeResolver.currentUsername(); // from the security context, never from the caller
        User user = find(id);
        requireNotSelf(user, actingUsername);
        // An Admin account is never deleted (deactivate it instead), whether or not it has a staff record
        if (user.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ADMIN_ACCOUNT_MESSAGE);
        }
        Optional<Staff> staff = staffRepository.findByUserUsername(user.getUsername());
        if (staff.isPresent()) {
            staffService.removeStaff(staff.get().getId()); // staff rules: inactive only, no assignment history
            return;
        }
        if (user.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, REMOVE_ACTIVE_MESSAGE);
        }
        if (taskRepository.existsByCreatedById(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, HAS_TASKS_MESSAGE);
        }
        if (supportRepository.existsByApprovedByUserId(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, HAS_APPROVALS_MESSAGE);
        }
        // The account's own notification choices go with it; nothing operational is deleted
        preferenceRepository.deleteAll(preferenceRepository.findByUserId(user.getId()));
        userRepository.delete(user);
        userRepository.flush();
    }

    private User find(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static void requireNotSelf(User user, String actingUsername) {
        if (actingUsername.equalsIgnoreCase(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, OWN_ACCOUNT_MESSAGE);
        }
    }

    private UserDto reload(UUID id) {
        User user = find(id);
        String staffName = staffRepository.findByUserUsername(user.getUsername()).map(Staff::getName).orElse(null);
        return UserDto.fromEntity(user, staffName);
    }
}
