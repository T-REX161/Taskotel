package com.hotel.workload.security;

import com.hotel.workload.staff.ManagerViewAccessService;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Administrator-only system role changes (STAFF, MANAGER, ADMIN in any direction). This is the only place a role is changed after an account
 * exists; the caller's identity always comes from the security context, never from the request.
 *
 * <p>Rules, all enforced here in the backend (the screens only mirror them):
 * <ul>
 *   <li>Only an Administrator may change a role (403 otherwise, whichever way the service is reached).</li>
 *   <li>Nobody changes their own role (400): a mistaken self-demotion could lock the system out.</li>
 *   <li>A change that involves ADMIN (to or from) needs the acting Administrator's current password again, with its own attempt limit.
 *       A wrong password is a 400, never a 401, because the browser treats 401 as an expired session.</li>
 *   <li>The last active Administrator is never demoted (409). The active Administrator rows are locked first, so two Administrators
 *       demoting each other at the same moment are processed one after the other.</li>
 *   <li>A new Manager must have a linked Staff record with an active primary department (409 otherwise). No additional department is ever
 *       granted by a promotion.</li>
 *   <li>Leaving the Manager role ends every additional-department grant (audited with the existing grant history). The Staff record, the
 *       primary department, tasks and assignments are never touched: only the role changes.</li>
 *   <li>The account's sessions end exactly: every token carries a session version (see {@code sessionsValidAfter}), the change moves it, and
 *       an older token (even one issued in the same second or millisecond) is refused from then on. The role is in any case read from the
 *       database on every request.</li>
 *   <li>Every change is recorded: actor, target, previous role, new role, time, and how many grants ended.</li>
 * </ul>
 */
@Service
public class RoleChangeService {

    public static final String ADMIN_ONLY_MESSAGE = "Only administrators can change system roles.";
    public static final String ROLE_REQUIRED_MESSAGE = "The new role is required.";
    public static final String UNCHANGED_MESSAGE = "The account already has that role. Nothing was changed.";
    public static final String OWN_ROLE_MESSAGE = "You cannot change your own role.";
    public static final String PASSWORD_REQUIRED_MESSAGE = "Enter your password to confirm a change that involves the administrator role.";
    public static final String WRONG_PASSWORD_MESSAGE = "The password is not correct.";
    public static final String TOO_MANY_MESSAGE = "Too many incorrect attempts. Please try again later.";
    public static final String LAST_ADMIN_MESSAGE = "Cannot remove the administrator role from the last active administrator.";
    public static final String NO_STAFF_RECORD_MESSAGE = "This account has no linked staff record, so it cannot become a Manager. A Manager's department comes from their staff record. Nothing was changed.";
    public static final String NO_DEPARTMENT_MESSAGE = "This account has no department. Assign an active department first: a Manager's own department comes from it. Nothing was changed.";
    public static final String INACTIVE_DEPARTMENT_MESSAGE = "This account's department is inactive. Move them to an active department first. Nothing was changed.";
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final UserRoleChangeRepository changes;
    private final ManagerViewAccessService viewAccess;
    private final CurrentUserScopeResolver scopeResolver;
    private final PasswordEncoder passwordEncoder;
    private final RoleChangeLimiter limiter;

    public RoleChangeService(UserRepository userRepository, StaffRepository staffRepository, UserRoleChangeRepository changes,
                             ManagerViewAccessService viewAccess, CurrentUserScopeResolver scopeResolver, PasswordEncoder passwordEncoder,
                             RoleChangeLimiter limiter) {
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.changes = changes;
        this.viewAccess = viewAccess;
        this.scopeResolver = scopeResolver;
        this.passwordEncoder = passwordEncoder;
        this.limiter = limiter;
    }

    @Transactional
    public UserDto changeRole(UUID targetId, Role newRole, String currentPassword, String client) {
        scopeResolver.requireAdmin(ADMIN_ONLY_MESSAGE);
        if (newRole == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ROLE_REQUIRED_MESSAGE);
        }
        String actorName = scopeResolver.currentUsername();

        // Lock order, always the same, so concurrent changes are decided one at a time and cannot deadlock: every active administrator
        // (by id), then the target. Everything below reads the rows as they are after the wait, never a stale copy.
        List<User> admins = userRepository.findActiveAdminsForUpdate();
        User actor = admins.stream().filter(admin -> admin.getUsername().equalsIgnoreCase(actorName)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, ADMIN_ONLY_MESSAGE)); // no longer an active administrator
        User target = userRepository.findByIdForUpdate(targetId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (target.getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, OWN_ROLE_MESSAGE);
        }
        Role previous = target.getRole();
        if (previous == newRole) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, UNCHANGED_MESSAGE);
        }

        boolean involvesAdmin = previous == Role.ADMIN || newRole == Role.ADMIN;
        if (involvesAdmin) {
            requireActorPassword(actor, currentPassword, client);
        }

        // Demoting an Administrator: the last active one is never demoted (the count comes from the rows locked above)
        if (previous == Role.ADMIN && target.isActive() && admins.size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, LAST_ADMIN_MESSAGE);
        }

        // Becoming a Manager: the linked Staff record and its active department must already be in place (nothing is granted or created)
        if (newRole == Role.MANAGER) {
            requireValidManagerRecord(target);
        }

        // Everything below changes data in this one transaction: any failure (including writing the history) undoes all of it.
        // Leaving the Manager role: every additional-department grant ends with it (audited like any other revocation)
        int revoked = previous == Role.MANAGER ? viewAccess.revokeAllForRoleChange(target, actor) : 0;

        target.setRole(newRole);
        // Sessions of this account end: every token carries the session version, which moves now (strictly up, whole milliseconds)
        target.setSessionsValidAfter(nextSessionVersion(target.getSessionsValidAfter()));
        userRepository.saveAndFlush(target);

        UserRoleChange record = new UserRoleChange();
        record.setTargetUserId(target.getId());
        record.setTargetUsername(target.getUsername());
        record.setPreviousRole(previous);
        record.setNewRole(newRole);
        record.setRevokedGrants(revoked);
        record.setChangedByUserId(actor.getId());
        record.setChangedByUsername(actor.getUsername());
        changes.saveAndFlush(record);

        if (involvesAdmin) {
            limiter.succeeded(client, actor.getUsername());
        }
        return UserDto.fromEntity(target, staffRepository.findByUserUsername(target.getUsername()).map(Staff::getName).orElse(null));
    }

    /** Now in whole milliseconds, but never equal to or before the previous value, so two changes never share a session version. */
    static LocalDateTime nextSessionVersion(LocalDateTime previous) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        return previous != null && !now.isAfter(previous) ? previous.plus(1, ChronoUnit.MILLIS) : now;
    }

    /** The role history of one account, newest first. Administrators only (also checked here, not just by the route). */
    @Transactional(readOnly = true)
    public List<UserRoleChange> history(UUID targetId) {
        scopeResolver.requireAdmin(ADMIN_ONLY_MESSAGE);
        if (!userRepository.existsById(targetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return changes.findByTargetUserIdOrderByChangedAtDesc(targetId);
    }

    private void requireActorPassword(User actor, String currentPassword, String client) {
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PASSWORD_REQUIRED_MESSAGE);
        }
        AttemptLimiter.Decision decision = limiter.begin(client, actor.getUsername());
        if (!decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_MESSAGE);
        }
        // A wrong password is a 400, never a 401: the client treats 401 as "session expired" and signs the Administrator out
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES
                || !passwordEncoder.matches(currentPassword, actor.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, WRONG_PASSWORD_MESSAGE);
        }
    }

    private void requireValidManagerRecord(User target) {
        Optional<Staff> staff = staffRepository.findByUserUsername(target.getUsername());
        if (staff.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, NO_STAFF_RECORD_MESSAGE);
        }
        if (staff.get().getDepartment() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, NO_DEPARTMENT_MESSAGE);
        }
        if (!staff.get().getDepartment().isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, INACTIVE_DEPARTMENT_MESSAGE);
        }
    }
}
