package com.hotel.workload.staff;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.PasswordValidatorService;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StaffServiceImpl implements StaffService {

    private static final List<TaskStatus> ACTIVE_TASK_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private static final String OWN_DEPARTMENT_ONLY_MESSAGE = "Managers can only manage staff in their own department.";
    private static final String TRANSFER_ADMIN_ONLY_MESSAGE = "Only administrators can transfer staff between departments.";
    /** Shown whenever a staff update names a different login email: the change belongs to the account holder, with verification. */
    public static final String LOGIN_EMAIL_MESSAGE = "A login email address cannot be changed here. The account holder must change it themselves from their own account settings, "
            + "where the new address is verified with a code and the current password is required.";

    private static final String DEACTIVATE_WITH_ACTIVE_ASSIGNMENTS_MESSAGE =
            "Cannot deactivate staff member with active task assignments. Please reassign or complete their active tasks first.";

    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordValidatorService passwordValidatorService;
    private final AssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final DepartmentResolver departmentResolver;
    private final CurrentUserScopeResolver scopeResolver;

    // Set by Spring (setter injection keeps the hand-built constructors used by unit tests unchanged). Null in those tests.
    private ManagerDepartmentAuditRecorder managerAuditRecorder;

    private com.hotel.workload.profile.EmailChangeService emailChangeService;

    @Autowired(required = false)
    void setEmailChangeService(com.hotel.workload.profile.EmailChangeService emailChangeService) {
        this.emailChangeService = emailChangeService;
    }

    @Autowired(required = false)
    void setManagerAuditRecorder(ManagerDepartmentAuditRecorder managerAuditRecorder) {
        this.managerAuditRecorder = managerAuditRecorder;
    }

    @Autowired
    public StaffServiceImpl(StaffRepository staffRepository,
                            UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            PasswordValidatorService passwordValidatorService,
                            AssignmentRepository assignmentRepository,
                            TaskRepository taskRepository,
                            DepartmentResolver departmentResolver,
                            CurrentUserScopeResolver scopeResolver) {
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidatorService = passwordValidatorService != null ? passwordValidatorService : new PasswordValidatorService();
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
        this.departmentResolver = departmentResolver;
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
    }

    public StaffServiceImpl(StaffRepository staffRepository,
                            UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            PasswordValidatorService passwordValidatorService,
                            DepartmentResolver departmentResolver,
                            CurrentUserScopeResolver scopeResolver) {
        this(staffRepository, userRepository, passwordEncoder, passwordValidatorService, null, null, departmentResolver, scopeResolver);
    }

    public StaffServiceImpl(StaffRepository staffRepository, UserRepository userRepository, PasswordEncoder passwordEncoder,
                            DepartmentResolver departmentResolver, CurrentUserScopeResolver scopeResolver) {
        this(staffRepository, userRepository, passwordEncoder, new PasswordValidatorService(), null, null, departmentResolver, scopeResolver);
    }

    @Override
    public List<StaffDto> getAllStaff() {
        DepartmentScope scope = requireStaffManagementScope();
        // An administrator sees every record. A Manager sees ordinary (STAFF-role) staff of their own department only: the department and
        // the role are part of the database query, so other departments' staff, and manager or admin accounts, are never loaded.
        List<Staff> visible = scope.isAll()
                ? staffRepository.findAll()
                : staffRepository.findByDepartmentAndUserRole(scope.department(), Role.STAFF);
        return visible.stream()
                .map(StaffDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public StaffDto createStaff(CreateStaffRequest request) {
        DepartmentScope scope = requireStaffManagementScope();
        // Managers may add staff only; manager and admin accounts can be created by an administrator
        Role role = request.getRole() != null ? request.getRole() : Role.STAFF;
        if (role != Role.STAFF && !scope.isAll()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators can create manager or admin accounts");
        }
        // A Manager adds staff to their own department only. The department in the request is never trusted: any other value is refused
        // before anything is looked up or saved (so the refusal says nothing about whether that department exists).
        // A Manager never creates Staff without a department: a missing one is a plain validation error, never silently defaulted
        if (scope.isDepartment() && (request.getDepartment() == null || request.getDepartment().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is required");
        }
        if (scope.isDepartment() && !isDepartmentCode(request.getDepartment(), scope.departmentCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, OWN_DEPARTMENT_ONLY_MESSAGE);
        }

        // Any account (STAFF, MANAGER or ADMIN) may be created without a department by an Administrator; a Manager's own request is already
        // held to their department above. A Manager account with no department gets no department-scoped access (CurrentUserScopeResolver
        // refuses it) until an Administrator assigns one; an Admin account keeps full global access either way.
        boolean noDepartment = request.getDepartment() == null || request.getDepartment().isBlank();

        passwordValidatorService.validate(request.getTemporaryPassword());

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        // Every account, whatever its role, starts unverified and must verify its email, then set its own password, before it can sign in
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getTemporaryPassword()));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setCredentialsSetupRequired(true);
        try {
            user = userRepository.save(user);
            // Flush now so a duplicate created concurrently surfaces here as a clean conflict, not at commit
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw duplicateAccount(e);
        }

        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName(request.getFullName());
        staff.setEmail(request.getEmail());
        staff.setPhone(request.getPhone());
        // For a Manager the department is the one resolved from their own records, never the one from the request
        staff.setDepartment(scope.isDepartment() ? scope.department() : (noDepartment ? null : departmentResolver.requireActive(request.getDepartment())));
        staff.setAvailability(request.getAvailability());
        staff.setActive(true);
        
        try {
            staff = staffRepository.save(staff);
            staffRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw duplicateAccount(e);
        }

        return StaffDto.fromEntity(staff);
    }

    @Override
    public boolean isUsernameAvailable(String username) {
        requireStaffManagementScope();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        return !userRepository.existsByUsername(username);
    }

    // The database unique constraints are the final authority when two requests race past the exists checks
    private ResponseStatusException duplicateAccount(DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase();
        String message = cause.contains("username") ? "Username already exists"
                : cause.contains("email") ? "Email already exists"
                : "Username or email already exists";
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    @Override
    @Transactional
    public StaffDto updateStaff(java.util.UUID id, UpdateStaffRequest request) {
        DepartmentScope scope = requireStaffManagementScope();
        // Lock the row first, so a concurrent assignment cannot slip in between the active-assignment check and the save
        staffRepository.lockByIds(java.util.Set.of(id));
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        requireInScope(scope, staff);
        requireMayManage(scope, staff);
        // Only an administrator transfers staff between departments; a Manager's request must keep the staff member's own department
        if (!scope.isAll() && !isDepartmentCode(request.getDepartment(), staff.getDepartment() == null ? null : staff.getDepartment().getCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, TRANSFER_ADMIN_ONLY_MESSAGE);
        }
        // The login email is never changed through this endpoint, by anyone (Admin included): it can only be changed by the account
        // holder through the verified self-service flow (EmailChangeService). A different address is refused, not silently ignored.
        requireLoginEmailUnchanged(staff, request.getEmail());

        // Reject before any field changes so a refused deactivation leaves the record untouched
        if (staff.isActive() && !request.isActive() && hasActiveAssignments(staff.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, DEACTIVATE_WITH_ACTIVE_ASSIGNMENTS_MESSAGE);
        }

        // A blank department clears it. Only an Administrator gets this far (a Manager's request must keep the department, above); any role
        // (STAFF, MANAGER, ADMIN) may be cleared. A cleared Manager loses department-scoped access at once (the scope resolver fails closed).
        boolean clearing = request.getDepartment() == null || request.getDepartment().isBlank();

        // Unchanged department: kept even if it is now inactive. Changed department: must be an active one.
        Department targetDepartment = clearing ? null : departmentResolver.forUpdate(staff.getDepartment(), request.getDepartment(), true);

        Department previousDepartment = staff.getDepartment();
        boolean departmentChanged = !Objects.equals(targetDepartment, previousDepartment);
        if (departmentChanged && targetDepartment == null && hasActiveAssignments(staff.getId())) {
            // Taking the department away from someone who holds active work would leave that work with an unassigned person
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "Cannot remove the department of %s because they have active assignments. Reassign or complete those tasks first.", staff.getName()));
        }
        if (departmentChanged && targetDepartment != null) {
            // The same rule the dedicated Manager department assignment uses (StaffTransferRules)
            StaffTransferRules.requireNoConflictingTasks(assignmentRepository, staff, targetDepartment);
        }

        staff.setName(request.getFullName());
        staff.setPhone(request.getPhone());
        staff.setDepartment(targetDepartment);
        staff.setAvailability(request.getAvailability());
        staff.setActive(request.isActive());

        User user = staff.getUser();
        if (user != null) {
            user.setActive(request.isActive());
        }

        staff = staffRepository.save(staff);
        // A Manager department change must always leave an audit record (Decision 17.6), also when an Admin makes it through this edit.
        // Same transaction: if the record cannot be written, the change is rolled back.
        // A first assignment (no previous department), a transfer and a removal (no new department) are all recorded (null side = Unassigned, V20)
        if (departmentChanged && managerAuditRecorder != null && ManagerDepartmentAuditRecorder.isManagerRecord(staff)) {
            staffRepository.flush();
            managerAuditRecorder.record(staff, previousDepartment, targetDepartment, ManagerDepartmentChange.SOURCE_STAFF_EDIT);
        }
        return StaffDto.fromEntity(staff);
    }

    @Override
    @Transactional
    public StaffDto deactivateStaff(java.util.UUID id) {
        DepartmentScope scope = requireStaffManagementScope();
        staffRepository.lockByIds(java.util.Set.of(id)); // see updateStaff
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        requireInScope(scope, staff);
        requireMayManage(scope, staff);

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
        DepartmentScope scope = requireStaffManagementScope();
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));

        requireInScope(scope, staff);
        requireMayManage(scope, staff);

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
        // Permanent removal is for administrators alone. It is decided by the same scope resolver as every other staff operation, not by
        // a separate authority check: a Manager (valid or not) and a Staff caller are refused with 403, and no sign-in is 401.
        scopeResolver.requireAdmin("Only administrators are authorized to remove staff members");

        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));

        // An Admin account is never deleted, by this path or by UserAccountService.remove (the same rule and message)
        if (staff.getUser() != null && staff.getUser().getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, com.hotel.workload.security.UserAccountService.ADMIN_ACCOUNT_MESSAGE);
        }

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
        // A department that names this person as its head must not keep pointing at a deleted record
        departmentResolver.clearHeadStaff(staff.getId());
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

    @Override
    @Transactional
    public com.hotel.workload.profile.EmailChangeService.CodeSent requestLoginEmailChange(UUID id, String newEmail, String client) {
        // The same checks, in the same order, as every other staff change: scope, then the row (locked), then department, then role
        DepartmentScope scope = requireStaffManagementScope();
        staffRepository.lockByIds(java.util.Set.of(id));
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));
        requireInScope(scope, staff); // 404 outside a Manager's department
        requireMayManage(scope, staff); // a Manager may not touch manager or admin accounts

        User account = staff.getUser();
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This staff member has no login account.");
        }
        if (account.getRole() != Role.STAFF) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A login email change can be requested for Staff accounts only.");
        }
        if (emailChangeService == null) {
            throw new IllegalStateException("Email change service is not available");
        }
        return emailChangeService.requestForAccount(account.getUsername(), client, newEmail);
    }

    /**
     * The email in an update must be the one the account already has (the screens send it back unchanged), ignoring case and surrounding
     * spaces. A missing email means "unchanged". Anything else is a request to change the login email, which this operation never does.
     */
    private static void requireLoginEmailUnchanged(Staff staff, String requestedEmail) {
        if (requestedEmail == null || requestedEmail.isBlank()) {
            return;
        }
        String requested = requestedEmail.trim();
        boolean sameAsStaffRecord = requested.equalsIgnoreCase(staff.getEmail());
        boolean sameAsLogin = staff.getUser() != null && requested.equalsIgnoreCase(staff.getUser().getEmail());
        if (!sameAsStaffRecord && !sameAsLogin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, LOGIN_EMAIL_MESSAGE);
        }
    }

    /**
     * Managers may manage ordinary staff accounts only. A staff record whose login belongs to a manager or an administrator can be
     * changed, deactivated or reactivated by an administrator alone, whichever endpoint is used, so a manager cannot disable or
     * re-email a higher-privileged account.
     */
    private void requireMayManage(DepartmentScope scope, Staff staff) {
        if (hasPrivilegedAccount(staff) && !scope.isAll()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators can change manager or admin accounts");
        }
    }

    /**
     * The scope for staff management: global for an administrator, one department for a valid Manager. Anyone else (a Staff member) is
     * refused, and an invalid Manager or a missing sign-in is refused by the resolver itself (403 / 401).
     */
    private DepartmentScope requireStaffManagementScope() {
        DepartmentScope scope = scopeResolver.resolve();
        if (!scope.isAll() && !scope.isDepartment()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to manage staff.");
        }
        return scope;
    }

    /**
     * A record outside the caller's scope is reported as not found, exactly as if it did not exist, so ids from another department
     * cannot be probed. Must run after the row is loaded (and locked), so a concurrent move is seen.
     */
    private static void requireInScope(DepartmentScope scope, Staff staff) {
        if (!scope.includes(staff.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found");
        }
    }

    private static boolean isDepartmentCode(String requested, String actual) {
        return requested != null && actual != null && requested.trim().equals(actual);
    }

    private static boolean hasPrivilegedAccount(Staff staff) {
        User account = staff.getUser();
        return account != null && (account.getRole() == Role.ADMIN || account.getRole() == Role.MANAGER);
    }
}
