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
 * an account. So an unknown identifier, an inactive account, an account with no active code, an expired code, a wrong code, a locked
 * account and a used-up code are all the same {@link #GENERIC_FAILURE} to the caller, and a send to an unknown or inactive account (or
 * one that is locked or cooling down) returns quietly: no email, no answer that differs. The real reason goes to the server log only,
 * without the identifier or the code. Every verify does one bcrypt comparison whether or not the account exists, and a send that does
 * not send does one bcrypt hash, so the hashing work does not tell the cases apart.
 *
 * <p><b>Failed attempts, lockout and cooldown (stage 2).</b> These live in the database, on the account row, and are decided while that
 * row is locked ({@code SELECT ... FOR UPDATE}), so simultaneous requests are handled one at a time and each sees the committed result
 * of the previous one:
 * <ul>
 *   <li>Failed attempts are <b>cumulative</b>: {@code verification_attempt_count} is not reset by a resend, a new code or an expired
 *       code. The fifth failure locks verification for {@value #LOCKOUT_MINUTES} minutes and clears the active code. While locked, every
 *       verification is refused (without counting) and no email is sent. When the lock has passed, the count starts again at zero.</li>
 *   <li><b>What counts as a failed attempt:</b> a wrong, missing, blank or malformed code submitted for an account that HAS an active,
 *       unexpired code. What does not count: an unknown or inactive account (there is no row), an account with no active code, an expired
 *       code, and anything submitted while locked. An expired or absent code cannot be guessed (it is checked, and refused, before the
 *       submitted value is looked at), so refusing it gives a guesser nothing and counting it would only push a person who typed the right
 *       code a little late towards a lockout. The volume of such requests is limited by the per-client limits instead.</li>
 *   <li>A successful verification clears the count, the lock and the resend cooldown (the person has proved they hold the code).</li>
 *   <li>A send within {@value #RESEND_COOLDOWN_SECONDS} seconds of the previous one is silently suppressed (same answer, nothing sent). The
 *       cooldown is reserved before the email is attempted and kept even if delivery fails; a failed delivery changes nothing else (an
 *       earlier valid code and the attempt count stay as they were).</li>
 * </ul>
 * Because unknown accounts have no row, they carry no cooldown or lock; their send is simply answered the same way. The in-memory
 * per-client limits in front of these endpoints are best effort only (per instance, cleared by a restart); this database state is what
 * holds across restarts.
 *
 * <p><b>Known limitation: timing.</b> Email dispatch is still synchronous (and runs while the account row is locked), so a send that
 * reaches SendGrid takes a network round trip while one that does not returns after a hash. A caller who measures response times can
 * still tell them apart. Asynchronous dispatch (a separately approved stage) is what closes this.
 */
@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeServiceImpl.class);

    public static final int EXPIRATION_MINUTES = 10;
    /** Cumulative failed attempts that trigger a lockout. */
    public static final int MAX_ATTEMPTS = 5;
    /** How long verification is refused after the last allowed failed attempt. */
    public static final int LOCKOUT_MINUTES = 15;
    /** The minimum time between two sends for one account. */
    public static final int RESEND_COOLDOWN_SECONDS = 60;

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
    // A failed delivery still commits (the reserved cooldown must stick); it changes nothing else, because the new code is stored only
    // after the email has been handed over.
    @Transactional(noRollbackFor = EmailSendingException.class)
    public void sendVerificationCode(String userIdentifier) {
        Optional<User> found = findActiveUserForUpdate(userIdentifier);
        if (found.isEmpty()) {
            // Unknown or inactive: nothing is sent or stored, and the caller is told nothing different. One hash is computed so the
            // work matches a real send.
            hashCode(generateCode());
            log.info("Verification code requested for an identifier that cannot be verified; nothing was sent");
            return;
        }
        User user = found.get();
        LocalDateTime now = LocalDateTime.now();

        releaseLockIfOver(user, now);
        if (isLocked(user, now)) {
            hashCode(generateCode());
            log.info("Verification code requested for user {} while verification is locked; nothing was sent", user.getUsername());
            return;
        }
        if (inCooldown(user, now)) {
            hashCode(generateCode());
            log.info("Verification code requested for user {} inside the resend cooldown; nothing was sent", user.getUsername());
            return;
        }

        String rawCode = generateCode();
        String codeHash = hashCode(rawCode);

        // Reserve the cooldown now (saved when the transaction commits, even if the delivery below fails)
        user.setVerificationLastSentAt(now);

        // Dispatch email first: if SendGrid rejects or is unconfigured, EmailSendingException is thrown before the new code is stored,
        // which preserves any existing valid code. Anything the client throws counts as a failed delivery.
        try {
            emailService.sendVerificationEmail(user.getEmail(), rawCode);
        } catch (EmailSendingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EmailSendingException("Failed to dispatch verification email", e);
        }

        // A new code replaces the old one, but the cumulative failed-attempt count is deliberately NOT reset here
        user.setVerificationCodeHash(codeHash);
        user.setVerificationCodeExpiresAt(now.plusMinutes(EXPIRATION_MINUTES));
        user.setVerificationCreatedAt(now);

        userRepository.save(user);

        log.info("Dispatched new email verification code for user: {}", user.getUsername());
    }

    @Override
    // The failed-attempt count, the lockout and the invalidation of a used-up or expired code are saved even though the call ends with an
    // exception; a rollback would silently undo them and the attempt limit would never take effect.
    @Transactional(noRollbackFor = VerificationException.class)
    public String verifyCode(String userIdentifier, String rawCode) {
        Optional<User> found = findActiveUserForUpdate(userIdentifier);

        // One bcrypt comparison for every request, whatever the account or the submitted code: the real code's hash when there is one,
        // otherwise a throw-away hash (whose result is never used). A null or badly formed code is compared too, as a fixed placeholder.
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
        LocalDateTime now = LocalDateTime.now();

        // 0. A lock that has run out is lifted (the count starts again); one still running refuses everything, even the right code,
        //    and counts nothing
        releaseLockIfOver(user, now);
        if (isLocked(user, now)) {
            log.warn("Verification attempted for user {} while verification is locked", user.getUsername());
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 1. Check if an active code exists (reject already-used codes)
        if (realHash == null) {
            log.warn("Verification attempted for user {} with no active verification code", user.getUsername());
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 2. A count already at the limit with no lock on record (only possible for data written before the lockout existed): lock now
        if (user.getVerificationAttemptCount() >= MAX_ATTEMPTS) {
            lock(user, now);
            userRepository.save(user);
            log.warn("Verification attempted for user {} with the attempt limit already reached; verification locked", user.getUsername());
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 3. Check if the code has expired (the cumulative count is left as it is)
        if (user.getVerificationCodeExpiresAt() == null || now.isAfter(user.getVerificationCodeExpiresAt())) {
            user.setVerificationCodeHash(null);
            user.setVerificationCodeExpiresAt(null);
            userRepository.save(user);
            log.warn("Expired verification code used for user: {}", user.getUsername());
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 4. A wrong or badly formed code counts as an attempt; the fifth locks verification. The caller is never told how many remain,
        //    or that a lock exists.
        if (!codeMatches) {
            int currentAttempts = user.getVerificationAttemptCount() + 1;
            user.setVerificationAttemptCount(currentAttempts);

            if (currentAttempts >= MAX_ATTEMPTS) {
                lock(user, now);
                userRepository.save(user);
                log.warn("Maximum verification attempts reached for user: {}. Code invalidated and verification locked.", user.getUsername());
            } else {
                userRepository.save(user);
                log.warn("Incorrect verification code submitted for user: {}. Remaining attempts: {}", user.getUsername(), MAX_ATTEMPTS - currentAttempts);
            }
            throw new VerificationException(GENERIC_FAILURE);
        }

        // 5. Successful verification: update fields, and clear the failed attempts, the lock and the cooldown
        user.setEmailVerified(true);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        user.setVerificationAttemptCount(0);
        user.setVerificationLockedUntil(null);
        user.setVerificationLastSentAt(null);

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

    // ── state rules ──────────────────────────────────────────────────────────

    private static boolean isLocked(User user, LocalDateTime now) {
        return user.getVerificationLockedUntil() != null && now.isBefore(user.getVerificationLockedUntil());
    }

    private static boolean inCooldown(User user, LocalDateTime now) {
        return user.getVerificationLastSentAt() != null
                && now.isBefore(user.getVerificationLastSentAt().plusSeconds(RESEND_COOLDOWN_SECONDS));
    }

    /** A lock whose time has passed is lifted and the cumulative count starts again from zero (saved when the transaction commits). */
    private static void releaseLockIfOver(User user, LocalDateTime now) {
        if (user.getVerificationLockedUntil() != null && !now.isBefore(user.getVerificationLockedUntil())) {
            user.setVerificationLockedUntil(null);
            user.setVerificationAttemptCount(0);
        }
    }

    /** Locks verification for the lockout period and clears the active code, so a guesser gets nothing more from this code. */
    private static void lock(User user, LocalDateTime now) {
        user.setVerificationLockedUntil(now.plusMinutes(LOCKOUT_MINUTES));
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
    }

    /**
     * The active account for a username or an email, locked for the rest of the transaction, or nothing. An inactive account is
     * treated exactly like one that does not exist. A blank identifier is a request error (it says nothing about any account).
     */
    private Optional<User> findActiveUserForUpdate(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new VerificationException("User identifier is required");
        }
        return userRepository.findByUsernameForUpdate(identifier)
                .or(() -> userRepository.findByEmailForUpdate(identifier))
                .filter(User::isActive);
    }
}
