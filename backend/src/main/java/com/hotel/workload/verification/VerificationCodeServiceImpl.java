package com.hotel.workload.verification;

import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeServiceImpl.class);

    public static final int EXPIRATION_MINUTES = 10;
    public static final int MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    public VerificationCodeServiceImpl(UserRepository userRepository,
                                       PasswordEncoder passwordEncoder,
                                       EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public void sendVerificationCode(String userIdentifier) {
        User user = findUserByIdentifier(userIdentifier);

        String rawCode = generateCode();
        String codeHash = hashCode(rawCode);
        LocalDateTime now = LocalDateTime.now();

        // Dispatch email first: if SendGrid rejects or is unconfigured,
        // EmailSendingException is thrown, preventing OTP generation from being stored
        // and preserving any existing valid code.
        emailService.sendVerificationEmail(user.getEmail(), rawCode);

        user.setVerificationCodeHash(codeHash);
        user.setVerificationCodeExpiresAt(now.plusMinutes(EXPIRATION_MINUTES));
        user.setVerificationCreatedAt(now);
        user.setVerificationAttemptCount(0);

        userRepository.save(user);

        log.info("Dispatched new email verification code for user: {}", user.getUsername());
    }

    @Override
    @Transactional
    public String verifyCode(String userIdentifier, String rawCode) {
        User user = findUserByIdentifier(userIdentifier);

        // 1. Check if an active code exists (reject already-used codes)
        if (user.getVerificationCodeHash() == null) {
            log.warn("Verification attempted for user {} with no active verification code", user.getUsername());
            throw new VerificationException("No active verification code found. Code may have already been used or expired.");
        }

        // 2. Check if maximum attempts have already been exceeded
        if (user.getVerificationAttemptCount() >= MAX_ATTEMPTS) {
            user.setVerificationCodeHash(null);
            user.setVerificationCodeExpiresAt(null);
            userRepository.save(user);
            log.warn("Verification attempted for user {} with maximum attempts already exceeded", user.getUsername());
            throw new VerificationException("Maximum verification attempts exceeded. Code has been invalidated. Please request a new verification code.");
        }

        // 3. Check if the code has expired
        if (user.getVerificationCodeExpiresAt() == null || LocalDateTime.now().isAfter(user.getVerificationCodeExpiresAt())) {
            user.setVerificationCodeHash(null);
            user.setVerificationCodeExpiresAt(null);
            userRepository.save(user);
            log.warn("Expired verification code used for user: {}", user.getUsername());
            throw new VerificationException("Verification code has expired. Please request a new verification code.");
        }

        // 4. Validate code format and check against stored hash
        boolean matches = (rawCode != null && rawCode.matches("^\\d{6}$"))
                && passwordEncoder.matches(rawCode, user.getVerificationCodeHash());

        if (!matches) {
            int currentAttempts = user.getVerificationAttemptCount() + 1;
            user.setVerificationAttemptCount(currentAttempts);

            if (currentAttempts >= MAX_ATTEMPTS) {
                user.setVerificationCodeHash(null);
                user.setVerificationCodeExpiresAt(null);
                userRepository.save(user);
                log.warn("Maximum verification attempts exceeded for user: {}. Code invalidated.", user.getUsername());
                throw new VerificationException("Maximum verification attempts exceeded. Code has been invalidated. Please request a new verification code.");
            } else {
                userRepository.save(user);
                int remaining = MAX_ATTEMPTS - currentAttempts;
                log.warn("Incorrect verification code submitted for user: {}. Remaining attempts: {}", user.getUsername(), remaining);
                throw new VerificationException("Invalid verification code. " + remaining + " attempts remaining.");
            }
        }

        // 5. Successful verification: update fields
        user.setEmailVerified(true);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        user.setVerificationAttemptCount(0);
        
        String setupToken = null;
        if (user.isCredentialsSetupRequired()) {
            setupToken = java.util.UUID.randomUUID().toString();
            user.setSetupToken(setupToken);
        }
        
        userRepository.save(user);

        log.info("Email verification successfully completed for user: {}", user.getUsername());
        return setupToken;
    }

    @Override
    public String generateCode() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    @Override
    public String hashCode(String rawCode) {
        return passwordEncoder.encode(rawCode);
    }

    private User findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new VerificationException("User identifier is required");
        }
        return userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier))
                .orElseThrow(() -> new UserNotFoundException("User not found: " + identifier));
    }
}
