package com.hotel.workload.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one place that decides whether an account may be deactivated: nobody deactivates their own account, and the last active
 * administrator is never deactivated. Every route that can switch an account off asks it first, so the rule cannot differ by route:
 * {@code PATCH /api/users/{id}/deactivate} (UserAccountService), {@code PATCH /api/staff/{id}/deactivate} and {@code PUT /api/staff/{id}}
 * with {@code active=false} (StaffServiceImpl).
 *
 * <p>The caller's identity always comes from the security context, never from the request. The check joins the caller's transaction
 * (MANDATORY) and, for an administrator target, locks the active administrator rows first, so two administrators deactivating each other
 * at the same moment are processed one after the other and the second one sees that only one is left.
 */
@Component
public class AdminAccountGuard {

    public static final String OWN_ACCOUNT_MESSAGE = "You cannot deactivate or remove your own account.";
    public static final String LAST_ADMIN_MESSAGE = "Cannot deactivate the last active administrator.";

    private final UserRepository userRepository;
    private final CurrentUserScopeResolver scopeResolver;

    public AdminAccountGuard(UserRepository userRepository, CurrentUserScopeResolver scopeResolver) {
        this.userRepository = userRepository;
        this.scopeResolver = scopeResolver;
    }

    /** 400 when the target is the signed-in caller's own account. */
    public void requireNotOwnAccount(User target) {
        if (scopeResolver.currentUsername().equalsIgnoreCase(target.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, OWN_ACCOUNT_MESSAGE);
        }
    }

    /**
     * Refuses to deactivate {@code target} when it is the caller's own account (400) or the last active administrator (409).
     * An account that is already inactive is not being deactivated, so only the own-account rule applies to it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireMayDeactivate(User target) {
        requireNotOwnAccount(target);
        if (target.isActive() && target.getRole() == Role.ADMIN && userRepository.findActiveAdminsForUpdate().size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, LAST_ADMIN_MESSAGE);
        }
    }
}
