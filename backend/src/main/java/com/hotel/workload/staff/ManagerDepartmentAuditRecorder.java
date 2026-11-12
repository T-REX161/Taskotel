package com.hotel.workload.staff;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Writes the audit record for a Manager department change. It joins the caller transaction (MANDATORY), so the record and the
 * department change commit or roll back together: there is never a change without a record, nor a record without a change.
 */
@Component
public class ManagerDepartmentAuditRecorder {

    private final ManagerDepartmentChangeRepository repository;
    private final UserRepository userRepository;

    public ManagerDepartmentAuditRecorder(ManagerDepartmentChangeRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /** True when this staff record belongs to a Manager account (the only accounts whose department changes are audited). */
    static boolean isManagerRecord(Staff staff) {
        return staff.getUser() != null && staff.getUser().getRole() == Role.MANAGER;
    }

    /**
     * Records one change. {@code previous} is null for a first assignment (Unassigned to a department) and {@code next} is null for a removal
     * (a department to Unassigned); the matching id, code and name columns are then null (V20). Both null is no change at all: nothing is written.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ManagerDepartmentChange record(Staff staff, Department previous, Department next, String source) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        if (previous == null && next == null) {
            return null;
        }
        // Fails (and so rolls the change back) when the acting Admin cannot be identified: no unattributed change
        User admin = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("The acting administrator could not be identified; the change was not saved."));

        ManagerDepartmentChange change = new ManagerDepartmentChange();
        change.setManagerUserId(staff.getUser().getId());
        change.setManagerUsername(staff.getUser().getUsername());
        change.setStaffId(staff.getId());
        change.setStaffName(staff.getName());
        if (previous != null) {
            change.setPreviousDepartmentId(previous.getId());
            change.setPreviousDepartmentCode(previous.getCode());
            change.setPreviousDepartmentName(previous.getName());
        }
        if (next != null) {
            change.setNewDepartmentId(next.getId());
            change.setNewDepartmentCode(next.getCode());
            change.setNewDepartmentName(next.getName());
        }
        change.setChangedByUserId(admin.getId());
        change.setChangedByUsername(admin.getUsername());
        change.setChangeSource(source);
        return repository.saveAndFlush(change);
    }
}
