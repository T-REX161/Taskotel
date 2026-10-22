package com.hotel.workload.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest loginRequest) {
        LoginResponse response = authService.authenticate(loginRequest);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/credentials/setup")
    public ResponseEntity<LoginResponse> setupCredentials(@jakarta.validation.Valid @RequestBody SetupCredentialsRequest request) {
        LoginResponse response = authService.setupCredentials(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(@RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@RequestBody ResetPasswordRequest request) {
        if (request.getNewPassword() == null || !request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidPasswordException("Passwords do not match");
        }
        passwordResetService.confirmReset(request);
        return ResponseEntity.ok().build();
    }
}
