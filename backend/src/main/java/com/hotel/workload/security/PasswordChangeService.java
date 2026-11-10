package com.hotel.workload.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * A signed-in user changing their own password. It is separate from first-time setup (which needs a one-time token) and from the
 * email password reset: a user who still has to finish setup cannot use this to get around it.
 *
 * <p>Order of checks: the cheap ones first (fields present, confirmation matches, the new password meets the rules), then the
 * rate limit, then the current password against the stored BCrypt hash, then that the new one is different. Messages never include
 * a password or hash.
 */
@Service
public class PasswordChangeService {

    static final String MISSING_MESSAGE = "Current password, new password and confirmation are all required.";
    static final String MISMATCH_MESSAGE = "New password and confirmation do not match.";
    static final String WRONG_CURRENT_MESSAGE = "Current password is incorrect.";
    static final String SAME_MESSAGE = "New password must be different from your current password.";
    static final String TOO_LONG_MESSAGE = "Password must be 72 characters or fewer.";
    static final String SETUP_PENDING_MESSAGE = "Finish setting up your account before changing your password.";
    static final String TOO_MANY_MESSAGE = "Too many attempts. Please wait a few minutes and try again.";
    /** BCrypt only uses the first 72 bytes, so a longer password would silently be shortened. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordValidatorService passwordValidator;
    private final PasswordChangeLimiter limiter;

    public PasswordChangeService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                 PasswordValidatorService passwordValidator, PasswordChangeLimiter limiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidator = passwordValidator;
        this.limiter = limiter;
    }

    /** Changes the password of {@code username} (the signed-in account, from the security context). Throws on any refusal; returns nothing. */
    public void change(String username, String client, ChangePasswordRequest request) {
        if (username == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));

        // Setup (first password) has its own required flow; this is for accounts that already finished it
        if (user.isCredentialsSetupRequired() || !user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, SETUP_PENDING_MESSAGE);
        }

        if (request == null || isBlank(request.currentPassword()) || isBlank(request.newPassword()) || isBlank(request.confirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MISSING_MESSAGE);
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MISMATCH_MESSAGE);
        }
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TOO_LONG_MESSAGE);
        }
        passwordValidator.validate(request.newPassword()); // InvalidPasswordException -> 400 with the rules

        AttemptLimiter.Decision decision = limiter.begin(client, username);
        if (!decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_MESSAGE);
        }
        // A wrong current password is a 400, never a 401: the client treats 401 as "session expired" and signs the user out
        if (request.currentPassword().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES
                || !passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, WRONG_CURRENT_MESSAGE);
        }
        if (request.newPassword().equals(request.currentPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SAME_MESSAGE);
        }

        userRepository.updatePassword(user.getId(), passwordEncoder.encode(request.newPassword()),
                LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); // milliseconds: the session password version is derived from it
        limiter.succeeded(client, username);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
