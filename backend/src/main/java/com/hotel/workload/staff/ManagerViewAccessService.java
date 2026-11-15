package com.hotel.workload.staff;

import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-only: which additional departments a Manager may VIEW (never manage).
 *
 * <p>Every operation, including the reads, asks the scope resolver first, so no sign-in is 401 and a Manager (whoever's grants they
 * name), a Staff member or an invalid Manager is 403, whatever the URL rules say. A grant changes only what the Manager can read
 * ({@code DepartmentScope.canView}); it never reaches write authorization, which looks at the primary department alone.
 * Grants and revocations are audited in the same transaction.
 */
@Service
public class ManagerViewAccessService {

    static final String ADMIN_ONLY_MESSAGE = "Only administrators can view or change a Manager's department access.";

    private final CurrentUserScopeResolver scopeResolver;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final ManagerViewGrantRepository grantRepository;
    private final ManagerViewGrantChangeRepository changeRepository;

    public ManagerViewAccessService(CurrentUserScopeResolver scopeResolver, UserRepository userRepository, StaffRepository staffRepository,
                                    DepartmentRepository departmentRepository, ManagerViewGrantRepository grantRepository,
                                    ManagerViewGrantChangeRepository changeRepository) {
        this.scopeResolver = scopeResolver;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.departmentRepository = departmentRepository;
        this.grantRepository = grantRepository;
        this.changeRepository = changeRepository;
    }

    @Transactional(readOnly = true)
    public ManagerViewAccessDtos.ViewAccess get(UUID userId) {
        scopeResolver.requireAdmin(ADMIN_ONLY_MESSAGE);
        return view(managerAccount(userId));
    }

    /**
     * Replaces the Manager's view-only departments with exactly {@code codes}. New grants need an existing, active department that is
     * not the Manager's own; a grant that already exists may be kept while its department is inactive (it simply shows nothing until the
     * department is active again). Nothing else is changed: not the primary department, not tasks, staff or assignments.
     */
    @Transactional
    public ManagerViewAccessDtos.ViewAccess replace(UUID userId, List<String> codes) {
        scopeResolver.requireAdmin(ADMIN_ONLY_MESSAGE);
        User manager = managerAccount(userId);
        if (codes == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The list of departments is required (an empty list removes all access).");
        }
        User admin = actingAdmin();
        Department primary = primaryDepartment(manager);

        Set<String> requested = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A department code is missing.");
            }
            requested.add(code.trim());
        }

        Map<String, ManagerViewGrant> current = new LinkedHashMap<>();
        for (ManagerViewGrant grant : grantRepository.findByManagerId(manager.getId())) {
            current.put(grant.getDepartment().getCode(), grant);
        }

        // Validate everything before changing anything
        Map<String, Department> toAdd = new LinkedHashMap<>();
        for (String code : requested) {
            if (primary != null && primary.getCode().equals(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        primary.getName() + " is this Manager's own department and is always included. Nothing was changed.");
            }
            if (current.containsKey(code)) {
                continue;
            }
            Department department = departmentRepository.findByCode(code)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown department: " + code + ". Nothing was changed."));
            if (!department.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Department '" + department.getName() + "' is inactive and cannot be granted. Nothing was changed.");
            }
            toAdd.put(code, department);
        }

        for (Map.Entry<String, ManagerViewGrant> entry : current.entrySet()) {
            if (!requested.contains(entry.getKey())) {
                ManagerViewGrant grant = entry.getValue();
                grantRepository.delete(grant);
                audit(manager, grant.getDepartment(), ManagerViewGrantChange.ACTION_REVOKED, ManagerViewGrantChange.SOURCE_ADMIN, admin);
            }
        }
        for (Department department : toAdd.values()) {
            ManagerViewGrant grant = new ManagerViewGrant();
            grant.setManager(manager);
            grant.setDepartment(department);
            grant.setGrantedByUserId(admin.getId());
            grant.setGrantedByUsername(admin.getUsername());
            grantRepository.save(grant);
            audit(manager, department, ManagerViewGrantChange.ACTION_GRANTED, ManagerViewGrantChange.SOURCE_ADMIN, admin);
        }
        grantRepository.flush();
        return view(manager);
    }

    @Transactional(readOnly = true)
    public List<ManagerViewAccessDtos.Change> history(UUID userId) {
        scopeResolver.requireAdmin(ADMIN_ONLY_MESSAGE);
        return changeRepository.findByManagerUserIdOrderByChangedAtDesc(userId).stream().map(ManagerViewAccessDtos.Change::of).toList();
    }

    /**
     * Called when an Administrator changes a Manager's primary department: a view grant for the new primary department is redundant
     * (that department is now fully theirs), so it is removed and audited. Runs inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void primaryDepartmentChanged(User manager, Department newPrimary) {
        if (manager == null || newPrimary == null || manager.getRole() != Role.MANAGER) {
            return;
        }
        for (ManagerViewGrant grant : grantRepository.findByManagerId(manager.getId())) {
            if (grant.getDepartment().getCode().equals(newPrimary.getCode())) {
                grantRepository.delete(grant);
                audit(manager, grant.getDepartment(), ManagerViewGrantChange.ACTION_REVOKED, ManagerViewGrantChange.SOURCE_PRIMARY_CHANGE,
                        actingAdmin());
            }
        }
    }

    private ManagerViewAccessDtos.ViewAccess view(User manager) {
        Department primary = primaryDepartment(manager);
        List<ManagerViewAccessDtos.Grant> grants = grantRepository.findByManagerId(manager.getId()).stream()
                .filter(grant -> primary == null || !grant.getDepartment().getCode().equals(primary.getCode()))
                .map(ManagerViewAccessDtos.Grant::of)
                .toList();
        return new ManagerViewAccessDtos.ViewAccess(manager.getId(), manager.getUsername(),
                primary == null ? null : primary.getCode(), primary == null ? null : primary.getName(), grants);
    }

    private void audit(User manager, Department department, String action, String source, User admin) {
        ManagerViewGrantChange change = new ManagerViewGrantChange();
        change.setManagerUserId(manager.getId());
        change.setManagerUsername(manager.getUsername());
        change.setDepartmentId(department.getId());
        change.setDepartmentCode(department.getCode());
        change.setDepartmentName(department.getName());
        change.setAction(action);
        change.setChangeSource(source);
        change.setChangedByUserId(admin.getId());
        change.setChangedByUsername(admin.getUsername());
        changeRepository.save(change);
    }

    private User managerAccount(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected user is not a Manager.");
        }
        return user;
    }

    private Department primaryDepartment(User manager) {
        return staffRepository.findByUserUsername(manager.getUsername()).map(Staff::getDepartment).orElse(null);
    }

    /** The signed-in Administrator, never a value from the request. A change that cannot be attributed is not saved. */
    private User actingAdmin() {
        String username = scopeResolver.currentUsername();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("The acting administrator could not be identified; the change was not saved."));
    }
}
