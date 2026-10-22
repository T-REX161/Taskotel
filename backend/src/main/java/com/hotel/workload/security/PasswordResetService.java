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

    @Transactional(noRollbackFor = Exception.class)
    public void requestReset(ForgotPasswordRequest request) {
        if (!emailService.isConfigured()) {
            throw new EmailSendingException("Email service is not configured. Cannot process password reset.");
        }

        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            return;
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        Optional<User> optionalUser = userRepository.findByEmail(normalizedEmail);

        if (optionalUser.isEmpty()) {
            return;
        }

        User user = optionalUser.get();

        if (!user.isActive()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (user.getPasswordResetLastSentAt() != null && 
            user.getPasswordResetLastSentAt().plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(now)) {
            return;
        }

        String code = generateSecureCode();
        user.setPasswordResetCodeHash(passwordEncoder.encode(code));
        user.setPasswordResetExpiresAt(now.plusMinutes(CODE_EXPIRATION_MINUTES));
        user.setPasswordResetAttemptCount(0);
        user.setPasswordResetLastSentAt(now);

        userRepository.save(user);

        emailService.sendPasswordResetEmail(user.getEmail(), code);
    }

    @Transactional
    public void confirmReset(ResetPasswordRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank() ||
            request.getCode() == null || request.getCode().isBlank()) {
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        Optional<User> optionalUser = userRepository.findByEmail(normalizedEmail);

        if (optionalUser.isEmpty()) {
            throw new InvalidVerificationCodeException("Invalid code or email.");
        }

        User user = optionalUser.get();

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
