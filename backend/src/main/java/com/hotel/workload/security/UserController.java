package com.hotel.workload.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** All of /api/users/** is ADMIN only (see SecurityConfig). The account acted on is the one in the path; the caller comes from the token. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserAccountService accountService;
    private final RoleChangeService roleChangeService;
    private final ClientAddressResolver clientAddress;

    public UserController(UserService userService, UserAccountService accountService, RoleChangeService roleChangeService,
                          ClientAddressResolver clientAddress) {
        this.userService = userService;
        this.accountService = accountService;
        this.roleChangeService = roleChangeService;
        this.clientAddress = clientAddress;
    }

    /** The requested role, and (for a change that involves ADMIN only) the acting Administrator's password. The account is named by the path. */
    public record RoleChangeBody(Role role, String currentPassword) {}

    /** One audit record, for Administrators only. */
    public record RoleChangeDto(java.util.UUID id, java.util.UUID targetUserId, String targetUsername, Role previousRole, Role newRole,
                                int revokedGrants, String changedBy, java.time.LocalDateTime changedAt) {
        static RoleChangeDto of(UserRoleChange change) {
            return new RoleChangeDto(change.getId(), change.getTargetUserId(), change.getTargetUsername(), change.getPreviousRole(),
                    change.getNewRole(), change.getRevokedGrants(), change.getChangedByUsername(), change.getChangedAt());
        }
    }

    /** Changes an account's system role. Administrators only; every rule is enforced in RoleChangeService. */
    @PutMapping("/{id}/role")
    public ResponseEntity<UserDto> changeRole(@PathVariable UUID id, @RequestBody RoleChangeBody body,
                                              jakarta.servlet.http.HttpServletRequest http) {
        return ResponseEntity.ok(roleChangeService.changeRole(id, body == null ? null : body.role(),
                body == null ? null : body.currentPassword(), clientAddress.resolve(http)));
    }

    @GetMapping("/{id}/role-history")
    public ResponseEntity<List<RoleChangeDto>> roleHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(roleChangeService.history(id).stream().map(RoleChangeDto::of).toList());
    }

    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<UserDto> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.deactivate(id));
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<UserDto> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.activate(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        accountService.remove(id);
        return ResponseEntity.noContent().build();
    }
}
