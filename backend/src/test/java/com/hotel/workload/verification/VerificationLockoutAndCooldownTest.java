package com.hotel.workload.verification;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Step 2J, stage 2: cumulative failed attempts, the 15-minute lockout and the 60-second resend cooldown, kept in the database.
 * Time is moved by editing the stored timestamps, not by waiting. Every test rolls back.
 */
@SpringBootTest
@Transactional
public class VerificationLockoutAndCooldownTest {

    private static final String CODE = "123456";
    private static final String WRONG = "000000";
    private static final String P = "vlc_";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User account(String name, boolean active) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(P + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("irrelevant"));
        user.setRole(Role.STAFF);
        user.setActive(active);
        user.setEmailVerified(false);
        user.setCredentialsSetupRequired(true);
        return userRepository.saveAndFlush(user);
    }

    private User withCode(User user, int attempts) {
        user.setVerificationCodeHash(passwordEncoder.encode(CODE));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(attempts);
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        userRepository.flush();
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private record Answer(int status, String body) {}

    private Answer postJson(String path, String json) throws Exception {
        MvcResult r = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        return new Answer(r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    private Answer send(String identifier) throws Exception {
        return postJson("/api/auth/email-verification/send", "{\"identifier\":\"" + identifier + "\"}");
    }

    private Answer attempt(String identifier, String code) throws Exception {
        return postJson("/api/auth/email-verification/verify", "{\"identifier\":\"" + identifier + "\",\"code\":\"" + code + "\"}");
    }

    private void failFiveTimes(User user) throws Exception {
        for (int i = 0; i < VerificationCodeServiceImpl.MAX_ATTEMPTS; i++) {
            assertEquals(400, attempt(user.getUsername(), WRONG).status());
        }
    }

    // ── lockout ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Five consecutive failures lock verification for 15 minutes and clear the active code; four do not")
    public void fiveFailuresTriggerLockout() throws Exception {
        User user = withCode(account("five", true), 0);
        LocalDateTime before = LocalDateTime.now();
        for (int i = 1; i <= 4; i++) {
            attempt(user.getUsername(), WRONG);
            User now = reload(user);
            assertEquals(i, now.getVerificationAttemptCount());
            assertNull(now.getVerificationLockedUntil(), "not locked after " + i + " failures");
            assertNotNull(now.getVerificationCodeHash());
        }
        attempt(user.getUsername(), WRONG);
        User locked = reload(user);
        assertEquals(5, locked.getVerificationAttemptCount());
        assertNotNull(locked.getVerificationLockedUntil(), "the fifth failure locks");
        Duration lock = Duration.between(before, locked.getVerificationLockedUntil());
        assertTrue(lock.toMinutes() >= 14 && lock.toMinutes() <= 15, "locked for about 15 minutes, was " + lock);
        assertNull(locked.getVerificationCodeHash(), "the active code is cleared by the lock");
    }

    @Test
    @DisplayName("During the lockout every verification is refused with the generic answer, even the right code, and nothing is counted")
    public void attemptsAreRejectedDuringLockout() throws Exception {
        User user = withCode(account("locked", true), 0);
        failFiveTimes(user);
        // Someone obtains a fresh valid code while the lock is running (stored directly): it must still be refused
        User locked = reload(user);
        locked.setVerificationCodeHash(passwordEncoder.encode(CODE));
        locked.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        userRepository.saveAndFlush(locked);

        Answer unknown = attempt(P + "nobody", CODE);
        for (String code : List.of(CODE, WRONG, WRONG)) {
            Answer answer = attempt(user.getUsername(), code);
            assertEquals(unknown, answer, "a locked account answers exactly like an unknown one");
        }
        User after = reload(user);
        assertEquals(5, after.getVerificationAttemptCount(), "nothing is counted during the lock");
        assertFalse(after.isEmailVerified(), "the right code does not verify during the lock");
    }

    @Test
    @DisplayName("When the lock has passed, attempts are accepted again and the count starts from zero")
    public void lockoutExpiryAllowsAttemptsAgain() throws Exception {
        User user = withCode(account("expiry", true), 0);
        failFiveTimes(user);
        User locked = reload(user);
        locked.setVerificationLockedUntil(LocalDateTime.now().minusSeconds(1)); // the lock has run out
        locked.setVerificationCodeHash(passwordEncoder.encode(CODE));            // and a new code was obtained afterwards
        locked.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        userRepository.saveAndFlush(locked);

        attempt(user.getUsername(), WRONG);
        User afterWrong = reload(user);
        assertNull(afterWrong.getVerificationLockedUntil(), "the expired lock is lifted");
        assertEquals(1, afterWrong.getVerificationAttemptCount(), "the count restarted from zero, then this failure counted");

        Answer ok = attempt(user.getUsername(), CODE);
        assertEquals(200, ok.status(), ok.body());
        assertTrue(reload(user).isEmailVerified());
    }

    @Test
    @DisplayName("A resend replaces the code but does not reset the cumulative failed attempts")
    public void resendDoesNotResetFailedAttempts() throws Exception {
        User user = withCode(account("resend", true), 3);
        String oldHash = user.getVerificationCodeHash();
        user.setVerificationLastSentAt(LocalDateTime.now().minusSeconds(120));
        userRepository.saveAndFlush(user);

        assertEquals(200, send(user.getUsername()).status());
        User after = reload(user);
        assertEquals(3, after.getVerificationAttemptCount(), "failed attempts survive a resend");
        assertNotEquals(oldHash, after.getVerificationCodeHash(), "the code was replaced");
        verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString());

        // and the attempts keep accumulating towards the lock across codes: two more failures lock it
        attempt(user.getUsername(), WRONG);
        attempt(user.getUsername(), WRONG);
        assertNotNull(reload(user).getVerificationLockedUntil(), "3 earlier + 2 new failures reach the limit");
    }

    @Test
    @DisplayName("An expired code does not change the failed-attempt count")
    public void expiredCodeLeavesTheCountAlone() throws Exception {
        User user = withCode(account("stale", true), 2);
        user.setVerificationCodeExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.saveAndFlush(user);
        assertEquals(400, attempt(user.getUsername(), CODE).status());
        assertEquals(2, reload(user).getVerificationAttemptCount());
    }

    @Test
    @DisplayName("A successful verification clears the failed attempts, the lock and the cooldown")
    public void successClearsEverything() throws Exception {
        User user = withCode(account("clear", true), 4);
        user.setVerificationLastSentAt(LocalDateTime.now().minusSeconds(10));
        userRepository.saveAndFlush(user);

        Answer ok = attempt(user.getUsername(), CODE);
        assertEquals(200, ok.status(), ok.body());
        User after = reload(user);
        assertTrue(after.isEmailVerified());
        assertEquals(0, after.getVerificationAttemptCount());
        assertNull(after.getVerificationLockedUntil());
        assertNull(after.getVerificationLastSentAt(), "the cooldown is cleared too, so the next sign-in can send a fresh code at once");
        assertNull(after.getVerificationCodeHash());
        assertTrue(ok.body().contains("setupToken"));
    }

    // ── cooldown ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A second send inside 60 seconds sends nothing and answers the same; after 60 seconds it sends again")
    public void resendCooldown() throws Exception {
        User user = account("cool", true);
        Answer first = send(user.getUsername());
        verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString());
        assertNotNull(reload(user).getVerificationLastSentAt());

        for (int i = 0; i < 3; i++) {
            assertEquals(first, send(user.getUsername()), "the answer never changes");
        }
        assertEquals(first, send(user.getEmail()), "the cooldown follows the account, not the identifier used");
        verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString());

        User u = reload(user);
        u.setVerificationLastSentAt(LocalDateTime.now().minusSeconds(59));
        userRepository.saveAndFlush(u);
        send(user.getUsername());
        verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString()); // 59 s: still cooling down

        u = reload(user);
        u.setVerificationLastSentAt(LocalDateTime.now().minusSeconds(61));
        userRepository.saveAndFlush(u);
        send(user.getUsername());
        verify(emailService, times(2)).sendVerificationEmail(anyString(), anyString()); // 61 s: sends again
    }

    @Test
    @DisplayName("Known, cooling-down, locked, unknown and inactive accounts all get the identical public answer to a send")
    public void sendAnswersAreIndistinguishable() throws Exception {
        account("known", true);
        User cooling = account("cooling", true);
        cooling.setVerificationLastSentAt(LocalDateTime.now().minusSeconds(5));
        userRepository.saveAndFlush(cooling);
        User locked = account("lockedsend", true);
        locked.setVerificationLockedUntil(LocalDateTime.now().plusMinutes(10));
        userRepository.saveAndFlush(locked);
        account("inactive", false);

        Answer expected = send(P + "nobody");
        assertEquals(200, expected.status());
        for (String identifier : List.of(P + "known", P + "cooling", P + "lockedsend", P + "inactive", P + "known@hotel.test", P + "nobody@hotel.test")) {
            assertEquals(expected, send(identifier), identifier);
        }
        String lower = expected.body().toLowerCase();
        assertFalse(lower.contains("lock") || lower.contains("cooldown") || lower.contains("wait") || lower.contains("minute") || lower.contains("attempt"));
    }

    @Test
    @DisplayName("A send while verification is locked sends nothing and changes nothing")
    public void sendDuringLockoutIsSilent() throws Exception {
        User user = account("lockedsilent", true);
        user.setVerificationLockedUntil(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(5);
        userRepository.saveAndFlush(user);

        send(user.getUsername());
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
        User after = reload(user);
        assertNull(after.getVerificationCodeHash());
        assertEquals(5, after.getVerificationAttemptCount());
        assertNotNull(after.getVerificationLockedUntil());
    }

    @Test
    @DisplayName("A send after the lock has passed lifts it, restarts the count and sends")
    public void sendAfterLockoutSends() throws Exception {
        User user = account("lockedover", true);
        user.setVerificationLockedUntil(LocalDateTime.now().minusSeconds(1));
        user.setVerificationAttemptCount(5);
        userRepository.saveAndFlush(user);

        send(user.getUsername());
        verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString());
        User after = reload(user);
        assertNull(after.getVerificationLockedUntil());
        assertEquals(0, after.getVerificationAttemptCount());
        assertNotNull(after.getVerificationCodeHash());
    }

    @Test
    @DisplayName("A failed delivery keeps the cooldown reserved but changes nothing else: an earlier code and the attempt count stay")
    public void failedDeliveryReservesCooldownOnly() throws Exception {
        User user = withCode(account("failcool", true), 2);
        String hash = user.getVerificationCodeHash();
        user.setVerificationLastSentAt(LocalDateTime.now().minusSeconds(120));
        userRepository.saveAndFlush(user);
        doThrow(new EmailSendingException("down")).when(emailService).sendVerificationEmail(anyString(), anyString());

        Answer first = send(user.getUsername());
        User after = reload(user);
        assertEquals(hash, after.getVerificationCodeHash(), "an earlier valid code is untouched");
        assertEquals(2, after.getVerificationAttemptCount());
        assertTrue(after.getVerificationLastSentAt().isAfter(LocalDateTime.now().minusSeconds(30)), "the cooldown is reserved");

        reset(emailService);
        assertEquals(first, send(user.getUsername()));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString()); // still cooling down
        assertEquals(200, attempt(user.getUsername(), CODE).status(), "the earlier code still works");
    }

    // ── nothing about attempts, locks or cooldowns leaks ─────────────────────

    @Test
    @DisplayName("No response ever mentions attempts, locks, minutes or remaining tries, and no internal identifier or timestamp is returned")
    public void noDetailsLeak() throws Exception {
        User user = withCode(account("quiet", true), 0);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            all.append(attempt(user.getUsername(), WRONG).body()).append('\n');
        }
        all.append(send(user.getUsername()).body()).append('\n').append(send(user.getUsername()).body());
        String lower = all.toString().toLowerCase();
        for (String word : List.of("attempt", "remaining", "locked", "lockout", "minute", "cooldown", "wait", "until", user.getId().toString(), "202")) {
            assertFalse(lower.contains(word), "the responses must not contain '" + word + "': " + all);
        }
    }

    // ── what counts as a failed attempt ──────────────────────────────────────

    @Test
    @DisplayName("Malformed codes sent over HTTP never verify, get the same generic failure as a wrong code, and count as a failed attempt against an active code")
    public void malformedCodesAreFailedAttemptsAndNeverVerify() throws Exception {
        Answer wrong = null;
        String[] malformed = {"", "   ", "abc", "12345", "1234567", "12 456", "1234.5", "\u0661\u0662\u0663\u0664\u0665\u0666", "-12345"};
        for (int i = 0; i < malformed.length; i++) {
            User user = withCode(account("mal" + i, true), 0);
            if (wrong == null) wrong = attempt(user.getUsername(), WRONG);
            User fresh = withCode(account("malb" + i, true), 0);

            Answer answer = attempt(fresh.getUsername(), malformed[i]);
            assertEquals(400, answer.status(), "code=[" + malformed[i] + "]");
            assertEquals(wrong, answer, "the same generic failure as a wrong code");
            User after = reload(fresh);
            assertFalse(after.isEmailVerified(), "a malformed code never verifies");
            assertEquals(1, after.getVerificationAttemptCount(), "it counts as one failed attempt");
            assertNotNull(after.getVerificationCodeHash(), "and leaves the active code in place");
            // A code that is missing entirely (no field at all) behaves the same
        }
        User noField = withCode(account("nofield", true), 0);
        Answer missing = postJson("/api/auth/email-verification/verify", "{\"identifier\":\"" + noField.getUsername() + "\"}");
        assertEquals(wrong, missing);
        assertEquals(1, reload(noField).getVerificationAttemptCount());
        assertFalse(reload(noField).isEmailVerified());
    }

    @Test
    @DisplayName("Submissions that cannot be guessed against do not count: no active code, an expired code, and an unknown account")
    public void onlyGuessesAgainstAnActiveCodeCount() throws Exception {
        User none = account("nocode", true);
        for (int i = 0; i < 8; i++) {
            assertEquals(400, attempt(none.getUsername(), WRONG).status());
        }
        assertEquals(0, reload(none).getVerificationAttemptCount(), "no active code: nothing counted");
        assertNull(reload(none).getVerificationLockedUntil(), "and no lock");

        User stale = withCode(account("expired2", true), 1);
        stale.setVerificationCodeExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.saveAndFlush(stale);
        attempt(stale.getUsername(), WRONG);
        attempt(stale.getUsername(), CODE);
        assertEquals(1, reload(stale).getVerificationAttemptCount(), "an expired code does not change the count");
        assertNull(reload(stale).getVerificationCodeHash(), "it is cleared on first use, so there is nothing left to guess");
    }

}
