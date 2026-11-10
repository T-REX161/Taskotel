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
import java.util.Optional;
import java.util.UUID;

/**
 * Email verification codes for the first-time sign-in flow.
 *
 * <p><b>What a caller can learn (Step 2J, stage 1).</b> The public endpoints must not reveal whether a username or email belongs to
 * an account. So an unknown identifier, an inactive account, an account with no active code, an expired code, a wrong code and a
 * used-up code are all the same {@link #GENERIC_FAILURE} to the caller, and a send to an unknown or inactive account returns quietly
 * (no email, no state change). The real reason for a failure goes to the server log only, without the identifier or the code.
 * Every verify does one bcrypt comparison whether or not the account exists (against a throw-away hash when it does not), and a send to
 * an unknown account does one bcrypt hash, so the hashing work does not tell the cases apart. The email round trip itself still takes
 * longer for a real account; closing that gap needs the email to be dispatched off the request path (a later stage).
 *
 * <p><b>Known limitation of this stage: timing.</b> Email dispatch is still synchronous, so a send to a real account takes a SendGrid
 * round trip (typically hundreds of milliseconds) while a send to an unknown or inactive account returns right after one hash. A caller
 * who measures response times can therefore still tell them apart. Stage 1 equalizes the status, the body and the hashing work; it does
 * not make the endpoint timing-resistant. Asynchronous dispatch (a separately approved stage) is what closes this.
 */
@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeServiceImpl.class);

    public static final int EXPIRATION_MINUTES = 10;
    public static final int MAX_ATTEMPTS = 5;

    /** The one message every failed verification gets, whatever the cause. */
    public static final String GENERIC_FAILURE = "Invalid or expired verification code.";

    /** Compared in place of a null or malformed code, so a comparison always happens; its result is never used. */
    private static final String PLACEHOLDER_CODE = "000000";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();
    /** A hash of a random value nobody knows: compared against when there is no real code to compare, so the work is the same. */
    private final String unknownAccountHash;

    public VerificationCodeServiceImpl(UserRepository userRepository,
                                       PasswordEncoder passwordEncoder,
                                       EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.unknownAccountHash = passwordEncoder == null ? null : passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public void sendVerificationCode(String userIdentifier) {
        Optional<User> found = findActiveUser(userIdentifier);
        if (found.isEmpty()) {
            // Unknown or inactive: nothing is sent or stored, and the caller is told nothing different. One hash is computed so the
            // work matches a real send.
            hashCode(generateCode());
            log.info("Verification code requested for an identifier that cannot be verified; nothing was sent");
            return;
        }
        User user = found.get();

        String rawCode = generateCode();
        String codeHash = hashCode(rawCode);
        LocalDateTime now = LocalDateTime.now();

        // Dispatch email first: if SendGrid rejects or is unconfigured,
        // EmailSendingException is thrown, preventing OTP generation from being stored
        // and preserving any existing valid code.
        try {
            emailService.sendVerificationEmail(user.getEmail(), rawCode);
        } catch (EmailSendingException e) {
            throw e;
        } catch (RuntimeException e) {
            // Anything the email client throws is a delivery failure, handled the same way (nothing stored)
            throw new EmailSendingException("Failed to dispatch verification email", e);
        }

        user.setVerificationCodeHash(codeHash);
        user.setVerificationCodeExpiresAt(now.plusMinutes(EXPIRATION_MINUTES));
        user.setVerificationCreatedAt(now);
        user.setVerificationAttemptCount(0);

        userRepository.save(user);

        log.info("Dispatched new email verification code for user: {}", user.getUsername());
    }

    @Override
    // The failed-attempt count and the invalidation of a used-up or expired code are saved even though the call ends with an
    // exception; a rollback would silently undo them and the five-attempt limit would never take effect.
    @Transactional(noRollbackFor = VerificationException.class)
    public String verifyCode(String userIdentifier, String rawCode) {
        Optional<User> found = findActiveUser(userIdentifier);

        // One bcrypt comparison for every request that has a well-formed code, whatever the account: the real code's hash when there
        // is one, otherwise a throw-away hash (whose result is never used).
        // A null or badly formed code is compared too (as a fixed placeholder whose result is ignored), so the work does not depend on
        // what was submitted either.
        boolean formatOk = rawCode != null && rawCode.matches("^\\d{6}$");
        String realHash = found.map(User::getVerificationCodeHash).orElse(null);
        String hashToCheck = realHash != null ? realHash : unknownAccountHash;
        boolean compared = hashToCheck != null && passwordEncoder.matches(formatOk ? rawCode : PLACEHOLDER_CODE, hashToCheck);
        boolean codeMatches = formatOk && compared;

        if (found.isEmpty()) {
            log.warn("Verification attempted for an identifier that cannot be verified");
            throw new VerificationException(GENERIC_FAILURE);
        }
        User user = found.get();

        // 1. Check if an active code exists (reject already-used codes)
        if (realHash == null) {
            log.warn("Verification attempted for user {} with no active verification code", user.getUsername());
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 2. Check if maximum attempts have already been exceeded
        if (user.getVerificationAttemptCount() >= MAX_ATTEMPTS) {
            user.setVerificationCodeHash(null);
            user.setVerificationCodeExpiresAt(null);
            userRepository.save(user);
            log.warn("Verification attempted for user {} with maximum attempts already exceeded", user.getUsername());
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 3. Check if the code has expired
        if (user.getVerificationCodeExpiresAt() == null || LocalDateTime.now().isAfter(user.getVerificationCodeExpiresAt())) {
            user.setVerificationCodeHash(null);
            user.setVerificationCodeExpiresAt(null);
            userRepository.save(user);
            log.warn("Expired verification code used for user: {}", user.getUsername());
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 4. A wrong or badly formed code counts as an attempt; the fifth invalidates the code. The caller is never told how many remain.
        if (!codeMatches) {
            int currentAttempts = user.getVerificationAttemptCount() + 1;
            user.setVerificationAttemptCount(currentAttempts);

            if (currentAttempts >= MAX_ATTEMPTS) {
                user.setVerificationCodeHash(null);
                user.setVerificationCodeExpiresAt(null);
                userRepository.save(user);
                log.warn("Maximum verification attempts exceeded for user: {}. Code invalidated.", user.getUsername());
            } else {
                userRepository.save(user);
                log.warn("Incorrect verification code submitted for user: {}. Remaining attempts: {}", user.getUsername(), MAX_ATTEMPTS - currentAttempts);
            }
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 5. Successful verification: update fields
        user.setEmailVerified(true);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        user.setVerificationAttemptCount(0);

        String setupToken = null;
        if (user.isCredentialsSetupRequired()) {
            setupToken = com.hotel.workload.security.SetupTokens.issue();
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

    /**
     * The active account for a username or an email, or nothing. An inactive account is treated exactly like one that does not exist.
     * A blank identifier is a request error (it says nothing about any account).
     */
    private Optional<User> findActiveUser(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new VerificationException("User identifier is required");
        }
        return userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier))
                .filter(User::isActive);
    }
}
