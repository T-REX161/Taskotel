package com.hotel.workload.security;

import com.hotel.workload.verification.EmailService;
import com.hotel.workload.verification.EmailSendingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int CODE_EXPIRATION_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordValidatorService passwordValidatorService;
    private final SecureRandom secureRandom;

    @Autowired
    public PasswordResetService(UserRepository userRepository,
                                EmailService emailService,
                                PasswordEncoder passwordEncoder,
                                PasswordValidatorService passwordValidatorService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidatorService = passwordValidatorService;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Starts a password reset for an email address. The caller is told nothing that depends on the account (Step 2M): an unknown address, a
     * deactivated account, an account inside its resend cooldown and an account whose email could not be delivered all end the same way, with
     * no error, so the answer cannot be used to find out which addresses have accounts. Only an email service that is not configured at all
     * is an error, and it is the same error for every request.
     *
     * <p>State is changed in this order, under a lock on the account row (so simultaneous requests are handled one at a time):
     * <ol>
     *   <li>the resend cooldown is reserved (it is kept even if delivery fails, so a failing provider is not hammered);</li>
     *   <li>the email with the new code is handed to the email service;</li>
     *   <li>only after that has succeeded is the new code stored, which replaces any earlier one.</li>
     * </ol>
     * If delivery fails nothing else changes: an earlier valid code stays valid with its attempt count, and no undelivered code is left
     * stored. The failure is logged (no address, no code).
     */
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        if (!emailService.isConfigured()) {
            throw new EmailSendingException("Email service is not configured. Cannot process password reset.");
        }

        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            return;
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        Optional<User> optionalUser = userRepository.findByEmailForUpdate(normalizedEmail);

        if (optionalUser.isEmpty() || !optionalUser.get().isActive()) {
            // Unknown or deactivated: nothing is sent or stored. One hash is computed so the work resembles a real request.
            spendHashingWork();
            return;
        }

        User user = optionalUser.get();
        LocalDateTime now = LocalDateTime.now();
        if (user.getPasswordResetLastSentAt() != null &&
            user.getPasswordResetLastSentAt().plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(now)) {
            spendHashingWork();
            return;
        }

        String code = generateSecureCode();
        String codeHash = passwordEncoder.encode(code);

        // Reserved first and saved when the transaction commits, even if the delivery below fails
        user.setPasswordResetLastSentAt(now);

        try {
            emailService.sendPasswordResetEmail(user.getEmail(), code);
        } catch (RuntimeException e) {
            // Delivery failed (EmailSendingException, or anything the email client throws). It is NOT reported to the caller: that would
            // tell an outsider which addresses have accounts. Nothing but the reserved cooldown has changed, so any earlier valid code
            // is untouched and no undelivered code is stored.
            logger.warn("A password reset email could not be delivered ({}); the existing reset code, if any, was left unchanged",
                    e.getClass().getSimpleName());
            return;
        }

        // Delivered: only now does the new code replace the old one. The attempt count restarts with the new code (as before).
        user.setPasswordResetCodeHash(codeHash);
        user.setPasswordResetExpiresAt(now.plusMinutes(CODE_EXPIRATION_MINUTES));
        user.setPasswordResetAttemptCount(0);

        userRepository.save(user);
    }

    private void spendHashingWork() {
        passwordEncoder.encode(generateSecureCode());
    }

    /**
     * Completes a reset with the emailed code. Every refusal is the same generic message. A wrong code is counted and the fifth cancels the
     * code; those changes must be SAVED even though the call ends in an exception, so the refusal does not roll the transaction back
     * (before Step 2M it did, and the attempt count was never actually stored). The account row is locked, so parallel guesses are all
     * counted and a code can be used only once.
     */
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public void confirmReset(ResetPasswordRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank() ||
            request.getCode() == null || request.getCode().isBlank()) {
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        Optional<User> optionalUser = userRepository.findByEmailForUpdate(normalizedEmail);

        if (optionalUser.isEmpty()) {
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        User user = optionalUser.get();

        // A deactivated account cannot reset its password (the same generic answer as an unknown email; nothing is changed)
        if (!user.isActive()) {
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        if (user.getPasswordResetCodeHash() == null || user.getPasswordResetExpiresAt() == null) {
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        if (LocalDateTime.now().isAfter(user.getPasswordResetExpiresAt())) {
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        if (user.getPasswordResetAttemptCount() >= MAX_ATTEMPTS) {
            clearResetFields(user);
            userRepository.save(user);
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        if (!passwordEncoder.matches(request.getCode(), user.getPasswordResetCodeHash())) {
            user.setPasswordResetAttemptCount(user.getPasswordResetAttemptCount() + 1);
            if (user.getPasswordResetAttemptCount() >= MAX_ATTEMPTS) {
                clearResetFields(user);
            }
            userRepository.save(user);
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        passwordValidatorService.validate(request.getNewPassword());

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        // Milliseconds: the session password version is derived from this value (see JwtUtil.passwordVersion)
        user.setPasswordChangedAt(java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        clearResetFields(user);
        userRepository.save(user);
    }

    private void clearResetFields(User user) {
        user.setPasswordResetCodeHash(null);
        user.setPasswordResetExpiresAt(null);
        user.setPasswordResetAttemptCount(0);
        user.setPasswordResetLastSentAt(null);
    }

    private String generateSecureCode() {
        int code = 100000 + secureRandom.nextInt(900000);
        return String.valueOf(code);
    }
}
