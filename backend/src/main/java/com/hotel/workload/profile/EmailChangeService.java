package com.hotel.workload.profile;

import com.hotel.workload.security.AttemptLimiter;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.verification.VerificationCodeService;
import com.hotel.workload.verification.VerificationCodeServiceImpl;
import com.hotel.workload.verification.EmailService;
import com.hotel.workload.verification.EmailSendingException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A signed-in user changing their own email address, with proof that they own the new one.
 *
 * <p>Flow: {@link #request} sends a six-digit code to the NEW address and remembers it as pending; nothing about the account's real
 * email changes. {@link #confirm} checks the code and only then replaces the email (on the user and on the linked staff record, which
 * mirrors it) and clears the pending change. A pending address is never used to sign in, and the username is never touched.
 *
 * <p>Reused rather than rebuilt: the secure code generator and hashing of {@link VerificationCodeService}, the same code lifetime
 * and attempt limit as first-time verification, the SendGrid email service, and the attempt limiter infrastructure. The pending
 * change lives in its own columns so it cannot interfere with first-time verification or password reset. The account is always the
 * signed-in one, taken from the security context; no request names another.
 */
@Service
public class EmailChangeService {

    private static final Logger log = LoggerFactory.getLogger(EmailChangeService.class);

    static final int CODE_LIFETIME_MINUTES = VerificationCodeServiceImpl.EXPIRATION_MINUTES;
    static final int MAX_ATTEMPTS = VerificationCodeServiceImpl.MAX_ATTEMPTS;
    static final int RESEND_COOLDOWN_SECONDS = 60;

    static final String INVALID_EMAIL = "Enter a valid email address.";
    static final String SAME_EMAIL = "That is already your email address.";
    static final String IN_USE = "This email address is already in use.";
    static final String SETUP_PENDING = "Finish setting up your account before changing your email address.";
    static final String TOO_MANY = "Too many attempts. Please wait a few minutes and try again.";
    static final String NONE_PENDING = "There is no email change in progress. Request a new code.";
    static final String EXPIRED = "That code has expired. Request a new code.";
    static final String ATTEMPTS_USED_UP = "Too many incorrect codes. Request a new code.";
    static final String CODE_FORMAT = "Enter the 6-digit code.";
    static final String PASSWORD_REQUIRED = "Enter your current password to change your email address.";
    static final String WRONG_PASSWORD = "Current password is incorrect.";
    /** BCrypt only uses the first 72 bytes (the same bound as the password change). */
    private static final int BCRYPT_MAX_BYTES = 72;

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

    /** What the screen needs after a code was sent: the masked new address and the timings. */
    public record CodeSent(String pendingEmailMasked, int expiresInSeconds, int resendAvailableInSeconds) {}

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final EmailService emailService;
    private final VerificationCodeService codes;
    private final PasswordEncoder passwordEncoder;
    private final EmailChangeLimiters.Request requestLimiter;
    private final EmailChangeLimiters.Confirm confirmLimiter;
    private final EntityManager entityManager;

    public EmailChangeService(UserRepository userRepository, StaffRepository staffRepository, EmailService emailService,
                              VerificationCodeService codes, PasswordEncoder passwordEncoder,
                              EmailChangeLimiters.Request requestLimiter, EmailChangeLimiters.Confirm confirmLimiter,
                              EntityManager entityManager) {
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.emailService = emailService;
        this.codes = codes;
        this.passwordEncoder = passwordEncoder;
        this.requestLimiter = requestLimiter;
        this.confirmLimiter = confirmLimiter;
        this.entityManager = entityManager;
    }

    /**
     * Sends a code to {@code newEmail} and remembers it as the pending address. Also used to resend (same address again): a new code
     * replaces the old one, but not more often than the cooldown allows. The email is sent before anything is stored, so a failed send
     * leaves nothing behind. The current address is told (best effort) that a change was requested.
     */
    @Transactional
    public CodeSent request(String username, String client, String newEmail, String currentPassword) {
        User user = lockedUser(username);
        requireOnboarded(user);

        // Checks that say nothing beyond what the caller typed come first and are not charged to the limiter (as before)
        String email = normalise(newEmail);
        if (email == null) throw bad(INVALID_EMAIL);
        if (email.equalsIgnoreCase(user.getEmail())) throw bad(SAME_EMAIL);

        // Every request that gets this far is charged, so wrong passwords and every refusal below are limited together
        AttemptLimiter.Decision decision = requestLimiter.begin(client, username);
        if (!decision.allowed()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);

        // Re-authentication (Step 2L): changing the email can hand the account over through a password reset, so a valid session alone is
        // not enough. The password is checked before anything that reveals more (whether another account uses the address), and the
        // account is the authenticated one, never one named by the caller.
        requireCurrentPassword(user, currentPassword);

        if (inUseByAnother(user, email)) throw new ResponseStatusException(HttpStatus.CONFLICT, IN_USE);

        LocalDateTime now = LocalDateTime.now();
        if (email.equals(user.getPendingEmail()) && user.getPendingEmailLastSentAt() != null) {
            long waited = Duration.between(user.getPendingEmailLastSentAt(), now).getSeconds();
            if (waited < RESEND_COOLDOWN_SECONDS) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Please wait " + (RESEND_COOLDOWN_SECONDS - waited) + " seconds before requesting another code.");
            }
        }
        String code = codes.generateCode();
        String hash = codes.hashCode(code);
        emailService.sendEmailChangeVerificationEmail(email, code); // throws if it cannot be sent: nothing is stored then

        // The current address must be told before the change is allowed to start. If that notice cannot be sent the request fails and
        // nothing is stored (the code that went to the new address is then useless), so a change can never be started silently.
        noticeOldAddress(user.getEmail(), email, false);

        user.setPendingEmail(email);
        user.setPendingEmailCodeHash(hash);
        user.setPendingEmailExpiresAt(now.plusMinutes(CODE_LIFETIME_MINUTES));
        user.setPendingEmailAttemptCount(0);
        user.setPendingEmailLastSentAt(now);
        userRepository.saveAndFlush(user);

        return new CodeSent(mask(email), CODE_LIFETIME_MINUTES * 60, RESEND_COOLDOWN_SECONDS);
    }

    /**
     * Checks the code and, only if it is right, replaces the account's email. Wrong guesses are counted (and saved even though the
     * call fails) and the code is cancelled after five; an expired or used-up code is cancelled; a correct code works once. Any
     * database failure while switching rolls everything back, so the existing email stays as it was.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void confirm(String username, String client, String code) {
        if (code == null || !code.matches("^\\d{6}$")) throw bad(CODE_FORMAT);
        User user = lockedUser(username);
        requireOnboarded(user);

        // Every well-formed attempt counts, whether or not a change is pending, so guessing cannot be spread out or probed
        AttemptLimiter.Decision decision = confirmLimiter.begin(client, username);
        if (!decision.allowed()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
        if (user.getPendingEmail() == null || user.getPendingEmailCodeHash() == null || user.getPendingEmailExpiresAt() == null) {
            throw bad(NONE_PENDING);
        }

        if (LocalDateTime.now().isAfter(user.getPendingEmailExpiresAt())) {
            user.clearPendingEmail();
            userRepository.saveAndFlush(user);
            throw bad(EXPIRED);
        }
        if (user.getPendingEmailAttemptCount() >= MAX_ATTEMPTS) {
            user.clearPendingEmail();
            userRepository.saveAndFlush(user);
            throw bad(ATTEMPTS_USED_UP);
        }
        if (!passwordEncoder.matches(code, user.getPendingEmailCodeHash())) {
            int used = user.getPendingEmailAttemptCount() + 1;
            if (used >= MAX_ATTEMPTS) {
                user.clearPendingEmail();
                userRepository.saveAndFlush(user);
                throw bad(ATTEMPTS_USED_UP);
            }
            user.setPendingEmailAttemptCount(used);
            userRepository.saveAndFlush(user);
            throw bad("Incorrect code. " + (MAX_ATTEMPTS - used) + " attempts remaining.");
        }

        String newEmail = user.getPendingEmail();
        if (inUseByAnother(user, newEmail)) { // someone else took it while the code was out
            user.clearPendingEmail();
            userRepository.saveAndFlush(user);
            throw new ResponseStatusException(HttpStatus.CONFLICT, IN_USE);
        }

        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        user.setEmailVerified(true); // proved by the code, and an account must already have been verified to get here
        user.clearPendingEmail();
        // A unique-constraint failure here propagates and rolls the whole transaction back: the email stays as it was
        userRepository.saveAndFlush(user);
        staffRepository.findByUserUsername(user.getUsername()).ifPresent((Staff staff) -> {
            staff.setEmail(newEmail); // the staff record mirrors the account's email everywhere else in the app
            staffRepository.saveAndFlush(staff);
        });

        // The old address is told the change happened, inside this transaction: if that notice cannot be sent the exception rolls the
        // switch back (the email stays as it was, the code stays usable) and the person tries again. A completed change therefore always
        // has a notice behind it; nothing is lost silently, at the price that the change waits for the email service.
        noticeOldAddress(oldEmail, newEmail, true);
        confirmLimiter.succeeded(client, username);
        log.info("Email address changed for user: {}", user.getUsername());
    }

    private void requireOnboarded(User user) {
        // Accounts that have not finished first-time verification and password setup must do that first, not use this to skip it
        if (user.isCredentialsSetupRequired() || !user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, SETUP_PENDING);
        }
    }

    private User lockedUser(String username) {
        if (username == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        User found = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
        // One request at a time per account, so two quick clicks cannot both pass the cooldown or both use the same code
        entityManager.find(User.class, found.getId(), LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(found);
        return found;
    }

    private boolean inUseByAnother(User me, String email) {
        boolean byUser = userRepository.findAllByEmailIgnoreCase(email).stream().anyMatch(other -> !other.getId().equals(me.getId()));
        if (byUser) return true;
        Optional<Staff> mine = staffRepository.findByUserUsername(me.getUsername());
        return staffRepository.findAllWithUser().stream()
                .anyMatch(s -> s.getEmail() != null && s.getEmail().equalsIgnoreCase(email) && (mine.isEmpty() || !s.getId().equals(mine.get().getId())));
    }

    /**
     * Tells the CURRENT address that a change was requested or completed. A failure is not swallowed: it is logged (no address, no code) and
     * rethrown as an EmailSendingException, which rolls the surrounding transaction back, so the change neither starts nor completes
     * without the owner of the current mailbox having been told.
     */
    private void noticeOldAddress(String oldEmail, String newEmail, boolean completed) {
        try {
            emailService.sendEmailChangeNotice(oldEmail, mask(newEmail), completed);
        } catch (EmailSendingException e) {
            log.warn("Could not send the email change notice; the change was not {}", completed ? "completed" : "started");
            throw e;
        } catch (RuntimeException e) {
            log.warn("Could not send the email change notice ({}); the change was not {}", e.getClass().getSimpleName(), completed ? "completed" : "started");
            throw new EmailSendingException("Could not send the email change notice", e);
        }
    }

    /** The signed-in user must type their current password. Missing: 400. Wrong: 400 (never 401, which the client reads as an expired session). */
    private void requireCurrentPassword(User user, String currentPassword) {
        if (currentPassword == null || currentPassword.isEmpty()) throw bad(PASSWORD_REQUIRED);
        if (currentPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw bad(WRONG_PASSWORD);
        }
    }

    /** Trimmed, lower-case, and only if it looks like one email address; otherwise null. */
    static String normalise(String raw) {
        if (raw == null) return null;
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) return null;
        return email;
    }

    /** "ma***@example.com": the first two characters of the local part and the whole domain. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        return (local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2)) + "***" + email.substring(at);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
