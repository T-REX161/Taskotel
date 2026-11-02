package com.hotel.workload.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public UserController(UserService userService, UserAccountService accountService) {
        this.userService = userService;
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<UserDto> deactivate(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(accountService.deactivate(id, authentication == null ? null : authentication.getName()));
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<UserDto> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.activate(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id, Authentication authentication) {
        accountService.remove(id, authentication == null ? null : authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
