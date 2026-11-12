package com.hotel.workload.staff;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The dedicated Admin operation that assigns or changes a Manager department.
 *
 * <p>A Manager department is the department of the linked Staff record and nothing else: this service changes that one field and
 * writes the audit record in the same transaction. The same transfer rule as the ordinary staff edit applies (see
 * {@link StaffTransferRules}), the target department must exist and be active, and existing tasks, assignments and support records
 * are never touched. Every operation, including the reads, is Admin-only: the scope resolver is asked first, so no sign-in is 401
 * and a Manager, a Staff member (or an invalid Manager) is 403, whatever the URL rules say.
 */
@Service
public class ManagerDepartmentServiceImpl implements ManagerDepartmentService {

    static final String ADMIN_ONLY_MESSAGE = "Only administrators can view or change a Manager department.";

    private final CurrentUserScopeResolver scopeResolver;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final DepartmentResolver departmentResolver;
    private final AssignmentRepository assignmentRepository;
    private final ManagerDepartmentAuditRecorder auditRecorder;
    private final ManagerDepartmentChangeRepository changeRepository;

    public ManagerDepartmentServiceImpl(CurrentUserScopeResolver scopeResolver, UserRepository userRepository,
                                        StaffRepository staffRepository, DepartmentResolver departmentResolver,
                                        AssignmentRepository assignmentRepository, ManagerDepartmentAuditRecorder auditRecorder,
                                        ManagerDepartmentChangeRepository changeRepository) {
        this.scopeResolver = scopeResolver;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.departmentResolver = departmentResolver;
        this.assignmentRepository = assignmentRepository;
        this.auditRecorder = auditRecorder;
        this.changeRepository = changeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ManagerDepartmentDtos.ManagerDepartment get(UUID userId) {
        requireAdmin();
        return ManagerDepartmentDtos.ManagerDepartment.of(linkedStaff(managerAccount(userId)));
    }

    @Override
    @Transactional
    public ManagerDepartmentDtos.ManagerDepartment assign(UUID userId, String departmentCode) {
        requireAdmin();
        User manager = managerAccount(userId);
        Staff found = linkedStaff(manager);

        // Lock the row first (the same lock-first pattern as the staff edit), so a concurrent assignment or move cannot slip in
        // between the checks below and the save; then read the record again so the checks see what is now committed
        staffRepository.lockByIds(Set.of(found.getId()));
        Staff staff = staffRepository.findById(found.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, noStaffMessage()));

        Department target = departmentResolver.requireActive(departmentCode);
        Department previous = staff.getDepartment();
        if (previous != null && previous.equals(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This Manager is already assigned to " + target.getName() + ". Nothing was changed.");
        }

        StaffTransferRules.requireNoConflictingTasks(assignmentRepository, staff, target);

        // One transaction: the department and its audit record are saved together or not at all
        staff.setDepartment(target);
        staff = staffRepository.saveAndFlush(staff);
        auditRecorder.record(staff, previous, target, ManagerDepartmentChange.SOURCE_ASSIGNMENT);
        return ManagerDepartmentDtos.ManagerDepartment.of(staff);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ManagerDepartmentDtos.Change> history(UUID userId) {
        requireAdmin();
        // Keyed by the user id alone: the history is kept (and readable) even if the account has since been removed
        return changeRepository.findHistory(userId).stream().map(ManagerDepartmentDtos.Change::of).toList();
    }

    private void requireAdmin() {
        scopeResolver.requireAdmin(ADMIN_ONLY_MESSAGE);
    }

    private User managerAccount(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected user is not a Manager.");
        }
        return user;
    }

    private Staff linkedStaff(User manager) {
        return staffRepository.findByUserUsername(manager.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, noStaffMessage()));
    }

    private static String noStaffMessage() {
        return "This Manager has no linked staff record, so a department cannot be assigned. Nothing was changed.";
    }
}
