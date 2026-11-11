package com.hotel.workload.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String TOO_MANY_REQUESTS_MESSAGE = "Too many requests. Please wait a few minutes and try again.";

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final PasswordResetRequestLimiter resetRequestLimiter;
    private final PasswordResetConfirmLimiter resetConfirmLimiter;
    private final CredentialSetupLimiter credentialSetupLimiter;
    private final ClientAddressResolver clientAddress;

    public AuthController(AuthService authService, PasswordResetService passwordResetService, LoginAttemptLimiter loginAttemptLimiter,
                          PasswordResetRequestLimiter resetRequestLimiter, PasswordResetConfirmLimiter resetConfirmLimiter,
                          CredentialSetupLimiter credentialSetupLimiter, ClientAddressResolver clientAddress) {
        this.clientAddress = clientAddress;
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.resetRequestLimiter = resetRequestLimiter;
        this.resetConfirmLimiter = resetConfirmLimiter;
        this.credentialSetupLimiter = credentialSetupLimiter;
    }

    /** The client identity for the limiters, from the one shared resolver (direct remote address unless a trusted proxy is configured). */
    private String client(jakarta.servlet.http.HttpServletRequest http) {
        return clientAddress.resolve(http);
    }

    /** A 429 with a generic message and Retry-After. It says nothing about the account, email, token or password. */
    private static ResponseEntity<java.util.Map<String, String>> tooManyRequests(AttemptLimiter.Decision decision, String message) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()))
                .body(java.util.Map.of("message", message));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, jakarta.servlet.http.HttpServletRequest http) {
        String client = client(http);
        AttemptLimiter.Decision decision = loginAttemptLimiter.begin(client, loginRequest.getUsername());
        if (!decision.allowed()) {
            return tooManyRequests(decision, "Too many sign-in attempts. Please wait a few minutes and try again.");
        }
        LoginResponse response = authService.authenticate(loginRequest);
        loginAttemptLimiter.succeeded(client, loginRequest.getUsername());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/credentials/setup")
    public ResponseEntity<?> setupCredentials(@jakarta.validation.Valid @RequestBody SetupCredentialsRequest request,
                                              jakarta.servlet.http.HttpServletRequest http) {
        // Keyed by client only: the token is the secret being guessed, so it is never used as a key
        String client = client(http);
        AttemptLimiter.Decision decision = credentialSetupLimiter.begin(client, "setup");
        if (!decision.allowed()) return tooManyRequests(decision, TOO_MANY_REQUESTS_MESSAGE);
        LoginResponse response = authService.setupCredentials(request);
        credentialSetupLimiter.succeeded(client, "setup");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<?> requestPasswordReset(@RequestBody ForgotPasswordRequest request, jakarta.servlet.http.HttpServletRequest http) {
        // Every request is counted whether or not the email belongs to an account, so the answer never reveals which do
        AttemptLimiter.Decision decision = resetRequestLimiter.begin(client(http), request.getEmail());
        if (!decision.allowed()) return tooManyRequests(decision, TOO_MANY_REQUESTS_MESSAGE);
        passwordResetService.requestReset(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<?> confirmPasswordReset(@RequestBody ResetPasswordRequest request, jakarta.servlet.http.HttpServletRequest http) {
        String client = client(http);
        AttemptLimiter.Decision decision = resetConfirmLimiter.begin(client, request.getEmail());
        if (!decision.allowed()) return tooManyRequests(decision, TOO_MANY_REQUESTS_MESSAGE);
        if (request.getNewPassword() == null || !request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidPasswordException("Passwords do not match");
        }
        passwordResetService.confirmReset(request);
        resetConfirmLimiter.succeeded(client, request.getEmail());
        return ResponseEntity.ok().build();
    }
}
