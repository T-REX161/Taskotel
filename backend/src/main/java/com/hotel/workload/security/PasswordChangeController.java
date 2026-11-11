package com.hotel.workload.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** PUT /api/profile/password: the signed-in user changes their own password. Under /api/**, so a valid token is required. */
@RestController
@RequestMapping("/api/profile")
public class PasswordChangeController {

    private final PasswordChangeService service;
    private final ClientAddressResolver clientAddress;

    public PasswordChangeController(PasswordChangeService service, ClientAddressResolver clientAddress) {
        this.service = service;
        this.clientAddress = clientAddress;
    }

    @PutMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ChangePasswordRequest request, Authentication authentication,
                                                              HttpServletRequest http) {
        // Whose password: only the authenticated account. The body has no user field, and nothing in it could name one.
        service.change(authentication == null ? null : authentication.getName(), clientAddress.resolve(http), request);
        return ResponseEntity.ok(Map.of("message", "Your password has been changed."));
    }
}
