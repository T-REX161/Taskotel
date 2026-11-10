package com.hotel.workload.security;

import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides, for the current request, how much data the signed-in user may reach (see {@link DepartmentScope}).
 *
 * <ul>
 *   <li><b>ADMIN</b> gets {@link DepartmentScope#all()}. No Staff record or department is needed.</li>
 *   <li><b>MANAGER</b> gets the department on their own, linked Staff record, but only while ALL of these hold, checked in the
 *       database on every call: the account exists, is active and is a MANAGER; a linked Staff record exists and is active; and that
 *       Staff record has a department that is active. If any of them fails the call is refused with HTTP 403 (fail closed), with one
 *       generic message that does not say which check failed.</li>
 *   <li><b>STAFF</b> gets {@link DepartmentScope#own()}: no department-level access. Existing Staff behaviour is unchanged.</li>
 *   <li>No authenticated user: HTTP 401. Any other or missing role: HTTP 403.</li>
 * </ul>
 *
 * The department is never read from the JWT, from request parameters or from anything the frontend sends. The role authority itself
 * comes from {@code JwtAuthenticationFilter}, which re-reads the role from the database on every request and rejects inactive or
 * deleted accounts; for a Manager the role is read again here as well, so a stale authority cannot widen access.
 */
@Component
@Transactional(readOnly = true) // class level: both public entry points run in one read-only transaction (resolve() calling resolve(auth) is a self-call the proxy cannot intercept)
public class CurrentUserScopeResolver {

    /** The one message a refused Manager sees. It deliberately does not reveal which condition failed. */
    static final String MANAGER_NOT_ASSIGNED_MESSAGE = "Your account is not assigned to an active department.";
    static final String ROLE_NOT_PERMITTED_MESSAGE = "You do not have permission to perform this action.";
    static final String SIGN_IN_MESSAGE = "Authentication is required.";

    private static final Logger logger = LoggerFactory.getLogger(CurrentUserScopeResolver.class);

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;

    public CurrentUserScopeResolver(UserRepository userRepository, StaffRepository staffRepository) {
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
    }

    /** The scope of the user authenticated in the current Spring Security context. */
    public DepartmentScope resolve() {
        return resolve(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * The scope of the given authentication.
     *
     * @throws ResponseStatusException 401 when nobody is authenticated; 403 for an invalid Manager or an unsupported role
     */
    public DepartmentScope resolve(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, SIGN_IN_MESSAGE);
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (authorities.contains("ROLE_ADMIN")) {
            return DepartmentScope.all();
        }
        if (authorities.contains("ROLE_MANAGER")) {
            return managerScope(authentication.getName());
        }
        if (authorities.contains("ROLE_STAFF")) {
            return DepartmentScope.own();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, ROLE_NOT_PERMITTED_MESSAGE);
    }

    /** The message a refused non-Admin sees from {@link #requireAdmin()}. */
    static final String ADMIN_ONLY_MESSAGE = "Only administrators can perform this action.";

    /**
     * Refuses everyone but an administrator: no sign-in is 401; a Manager (valid or not) or a Staff member is 403. The one place where
     * "Admin only" is decided inside a service, so every Admin-only operation answers the same way whatever route reaches it.
     */
    public DepartmentScope requireAdmin() {
        return requireAdmin(ADMIN_ONLY_MESSAGE);
    }

    /** As {@link #requireAdmin()}, with an operation-specific 403 message. */
    public DepartmentScope requireAdmin(String forbiddenMessage) {
        DepartmentScope scope = resolve();
        if (!scope.isAll()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage);
        }
        return scope;
    }

    /**
     * The user name of the signed-in caller, taken from the security context and never from a caller-supplied value.
     * No sign-in: 401.
     */
    public String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, SIGN_IN_MESSAGE);
        }
        return authentication.getName();
    }

    private DepartmentScope managerScope(String username) {
        Optional<User> account = userRepository.findByUsername(username);
        if (account.isEmpty()) {
            return refuse("account not found");
        }
        if (!account.get().isActive()) {
            return refuse("account inactive");
        }
        if (account.get().getRole() != Role.MANAGER) {
            return refuse("account is not a manager");
        }

        Optional<Staff> staff = staffRepository.findByUserUsername(username);
        if (staff.isEmpty()) {
            return refuse("no linked staff record");
        }
        if (!staff.get().isActive()) {
            return refuse("staff record inactive");
        }

        Department department = staff.get().getDepartment();
        if (department == null) {
            return refuse("no department");
        }
        if (!department.isActive()) {
            return refuse("department inactive");
        }
        return DepartmentScope.department(department);
    }

    /** Always throws 403. The reason goes to the log only (no username, no personal data), never to the caller. */
    private DepartmentScope refuse(String reason) {
        logger.warn("Manager department scope refused: {}", reason);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, MANAGER_NOT_ASSIGNED_MESSAGE);
    }
}
