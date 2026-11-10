package com.hotel.workload.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Step 2M: the public password-reset request must say nothing that depends on the account, and a failed email must never cost a person
 * their valid reset code. Real security chain and real (H2) database; every test rolls back. (What is really committed to the database,
 * and what happens under concurrency, is in {@link PasswordResetPersistenceTest}.)
 */
@SpringBootTest
@Transactional
public class PasswordResetRequestSafetyTest {

    private static final String OLD_PASSWORD = "Old@Pass123";
    private static final String NEW_PASSWORD = "Brand@New123";
    private static final String CODE = "123456";
    private static final String P = "prs_";
    private static final AtomicInteger CLIENTS = new AtomicInteger();

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User account(String name, Role role, boolean active) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(P + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private User withCode(User user, String code, int attempts) {
        user.setPasswordResetCodeHash(passwordEncoder.encode(code));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setPasswordResetAttemptCount(attempts);
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        userRepository.flush();
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private record Answer(int status, String body) {}

    private MvcResult send(MockHttpServletRequestBuilder builder, String json) throws Exception {
        int n = CLIENTS.incrementAndGet(); // a fresh client address per call, so the in-memory limits never interfere
        String client = "10.99." + (n / 250) + "." + (n % 250 + 1);
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr(client); return r; }).content(json)).andReturn();
    }

    private Answer requestReset(String email) throws Exception {
        MvcResult r = send(post("/api/auth/password-reset/request"), "{\"email\":\"" + email + "\"}");
        return new Answer(r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    private Answer confirm(String email, String code, String password) throws Exception {
        MvcResult r = send(post("/api/auth/password-reset/confirm"), "{\"email\":\"" + email + "\",\"code\":\"" + code
                + "\",\"newPassword\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}");
        return new Answer(r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    private String lastEmailedCode(String toEmail) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendPasswordResetEmail(to.capture(), code.capture());
        int last = to.getAllValues().size() - 1;
        assertEquals(toEmail, to.getAllValues().get(last));
        return code.getAllValues().get(last);
    }

    private String loginToken(User user, String password) throws Exception {
        MvcResult r = send(post("/api/auth/login"), "{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private int probe(String token) throws Exception {
        return mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    // ── A. no account enumeration ────────────────────────────────────────────

    @Test
    @DisplayName("Known, unknown, deactivated and differently-cased addresses all get the identical response")
    public void answersAreIdentical() throws Exception {
        account("known", Role.STAFF, true);
        account("inactive", Role.STAFF, false);

        Answer unknown = requestReset(P + "nobody@hotel.test");
        assertEquals(200, unknown.status());
        for (String email : List.of(P + "known@hotel.test", (P + "known@hotel.test").toUpperCase(), P + "inactive@hotel.test", "  " + P + "known@hotel.test  ")) {
            assertEquals(unknown, requestReset(email), email);
        }
        assertFalse(unknown.body().contains(P), "nothing about any address in the answer");
    }

    @Test
    @DisplayName("Only an active, known account is emailed; a deactivated account's reset state is left untouched")
    public void onlyActiveKnownAccountsAreEmailed() throws Exception {
        account("known", Role.STAFF, true);
        User inactive = account("inactive", Role.STAFF, false);

        requestReset(P + "nobody@hotel.test");
        requestReset(P + "inactive@hotel.test");
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
        assertNull(reload(inactive).getPasswordResetCodeHash());
        assertNull(reload(inactive).getPasswordResetLastSentAt());

        requestReset(P + "known@hotel.test");
        verify(emailService, times(1)).sendPasswordResetEmail(eq(P + "known@hotel.test"), anyString());
    }

    // ── B. a failed email is safe and invisible ──────────────────────────────

    @Test
    @DisplayName("When the email cannot be delivered the answer is identical to an unknown address, whatever the failure was")
    public void deliveryFailureDoesNotExposeTheAccount() throws Exception {
        account("known", Role.STAFF, true);
        Answer unknown = requestReset(P + "nobody@hotel.test");

        for (RuntimeException failure : List.of(new EmailSendingException("SendGrid rejected request"), new IllegalStateException("socket closed"), new NullPointerException())) {
            // Each failure is tried outside the cooldown the previous attempt reserved
            User known = userRepository.findByUsername(P + "known").orElseThrow();
            known.setPasswordResetLastSentAt(null);
            userRepository.saveAndFlush(known);
            reset(emailService);
            when(emailService.isConfigured()).thenReturn(true);
            doThrow(failure).when(emailService).sendPasswordResetEmail(anyString(), anyString());

            Answer answer = requestReset(P + "known@hotel.test");
            assertEquals(unknown, answer, failure.getClass().getSimpleName() + " must look like an unknown address");
            verify(emailService, times(1)).sendPasswordResetEmail(anyString(), anyString()); // it really was attempted
            String lower = answer.body().toLowerCase();
            assertFalse(lower.contains("send") || lower.contains("fail") || lower.contains("error") || lower.contains("grid"), answer.body());
        }
    }

    @Test
    @DisplayName("A failed delivery stores no code when there was none")
    public void failedDeliveryLeavesNoUnusableCode() throws Exception {
        User user = account("nocode", Role.STAFF, true);
        doThrow(new EmailSendingException("down")).when(emailService).sendPasswordResetEmail(anyString(), anyString());

        requestReset(P + "nocode@hotel.test");
        User after = reload(user);
        assertNull(after.getPasswordResetCodeHash(), "no undelivered code is left stored");
        assertNull(after.getPasswordResetExpiresAt());
        assertNotNull(after.getPasswordResetLastSentAt(), "the cooldown is reserved");
    }

    @Test
    @DisplayName("A failed resend does not replace or weaken an earlier valid code: its hash, expiry and attempt count stay, and it still works")
    public void failedResendPreservesTheEarlierCode() throws Exception {
        User user = withCode(account("keep", Role.STAFF, true), CODE, 2);
        String hash = user.getPasswordResetCodeHash();
        LocalDateTime expiry = user.getPasswordResetExpiresAt();
        user.setPasswordResetLastSentAt(LocalDateTime.now().minusSeconds(120));
        userRepository.saveAndFlush(user);
        doThrow(new EmailSendingException("down")).when(emailService).sendPasswordResetEmail(anyString(), anyString());

        requestReset(P + "keep@hotel.test");
        User after = reload(user);
        assertEquals(hash, after.getPasswordResetCodeHash());
        assertEquals(expiry, after.getPasswordResetExpiresAt());
        assertEquals(2, after.getPasswordResetAttemptCount());

        assertEquals(200, confirm(P + "keep@hotel.test", CODE, NEW_PASSWORD).status(), "the earlier code still resets the password");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, reload(user).getPassword()));
    }

    @Test
    @DisplayName("A successful resend replaces the code only AFTER delivery: during the send the old code is still the stored one, afterwards the new one is")
    public void newCodeIsStoredOnlyAfterDelivery() throws Exception {
        User user = withCode(account("order", Role.STAFF, true), CODE, 1);
        String oldHash = user.getPasswordResetCodeHash();
        user.setPasswordResetLastSentAt(LocalDateTime.now().minusSeconds(120));
        userRepository.saveAndFlush(user);

        doAnswer(invocation -> {
            // The moment the email is handed over: nothing has been replaced yet
            assertEquals(oldHash, userRepository.findById(user.getId()).orElseThrow().getPasswordResetCodeHash(), "stored before delivery succeeded");
            return null;
        }).when(emailService).sendPasswordResetEmail(anyString(), anyString());

        assertEquals(200, requestReset(P + "order@hotel.test").status());
        String emailed = lastEmailedCode(P + "order@hotel.test");

        User after = reload(user);
        assertNotEquals(oldHash, after.getPasswordResetCodeHash(), "replaced once delivered");
        assertTrue(passwordEncoder.matches(emailed, after.getPasswordResetCodeHash()), "the stored code is the delivered one");
        assertEquals(0, after.getPasswordResetAttemptCount(), "the attempt count restarts with the new code (existing behaviour)");
        if (!emailed.equals(CODE)) {
            assertEquals(400, confirm(P + "order@hotel.test", CODE, NEW_PASSWORD).status(), "the replaced code no longer works");
        }
        assertEquals(200, confirm(P + "order@hotel.test", emailed, NEW_PASSWORD).status(), "the new one does");
    }

    @Test
    @DisplayName("The 60-second cooldown suppresses a second request with the same answer, even after a failed delivery; after it, a resend works")
    public void cooldown() throws Exception {
        User user = account("cool", Role.STAFF, true);
        Answer first = requestReset(P + "cool@hotel.test");
        verify(emailService, times(1)).sendPasswordResetEmail(anyString(), anyString());
        assertEquals(first, requestReset(P + "cool@hotel.test"));
        verify(emailService, times(1)).sendPasswordResetEmail(anyString(), anyString());

        User u = reload(user);
        u.setPasswordResetLastSentAt(LocalDateTime.now().minusSeconds(61));
        userRepository.saveAndFlush(u);
        assertEquals(first, requestReset(P + "cool@hotel.test"));
        verify(emailService, times(2)).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("An email service that is not configured at all is the same error for every address, known or not")
    public void unconfiguredServiceIsTheSameForEveryone() throws Exception {
        account("known", Role.STAFF, true);
        when(emailService.isConfigured()).thenReturn(false);
        Answer unknown = requestReset(P + "nobody@hotel.test");
        assertEquals(500, unknown.status());
        assertEquals(unknown, requestReset(P + "known@hotel.test"));
    }

    // ── C. the reset rules are unchanged ─────────────────────────────────────

    @Test
    @DisplayName("Expired and already-used codes stay invalid, with the same generic answer, and never change the password")
    public void expiredAndUsedCodesStayInvalid() throws Exception {
        User expired = withCode(account("expired", Role.STAFF, true), CODE, 0);
        expired.setPasswordResetExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.saveAndFlush(expired);
        Answer unknown = confirm(P + "nobody@hotel.test", CODE, NEW_PASSWORD);
        assertEquals(400, unknown.status());
        assertEquals(unknown, confirm(P + "expired@hotel.test", CODE, NEW_PASSWORD), "an expired code answers like an unknown address");
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, reload(expired).getPassword()));

        User used = withCode(account("used", Role.STAFF, true), CODE, 0);
        assertEquals(200, confirm(P + "used@hotel.test", CODE, NEW_PASSWORD).status());
        assertEquals(unknown, confirm(P + "used@hotel.test", CODE, "Another@Pass456"), "a used code cannot be used again");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, reload(used).getPassword()), "the second attempt changed nothing");
    }

    @Test
    @DisplayName("Wrong codes stay limited: five cancel the code (from five different clients, so only the stored limit applies), and then even the right code is refused")
    public void wrongCodesStayLimited() throws Exception {
        User user = withCode(account("limit", Role.STAFF, true), CODE, 0);
        for (int i = 1; i <= 5; i++) {
            assertEquals(400, confirm(P + "limit@hotel.test", "000000", NEW_PASSWORD).status(), "wrong code " + i);
        }
        User after = reload(user);
        assertNull(after.getPasswordResetCodeHash(), "the fifth wrong code cancelled the reset code");
        assertEquals(400, confirm(P + "limit@hotel.test", CODE, NEW_PASSWORD).status());
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, reload(user).getPassword()));
    }

    @Test
    @DisplayName("Password strength is still enforced: a weak new password is refused and the code stays usable")
    public void weakPasswordStillRefused() throws Exception {
        User user = withCode(account("weak", Role.STAFF, true), CODE, 0);
        assertEquals(400, confirm(P + "weak@hotel.test", CODE, "weak").status());
        assertNotNull(reload(user).getPasswordResetCodeHash(), "a refused weak password does not use up the code");
        assertEquals(200, confirm(P + "weak@hotel.test", CODE, NEW_PASSWORD).status());
    }

    @Test
    @DisplayName("A deactivated account cannot reset its password even with a valid code, and gets the unknown-address answer")
    public void inactiveAccountsCannotReset() throws Exception {
        User user = withCode(account("dead", Role.STAFF, false), CODE, 0);
        Answer unknown = confirm(P + "nobody@hotel.test", CODE, NEW_PASSWORD);
        assertEquals(unknown, confirm(P + "dead@hotel.test", CODE, NEW_PASSWORD));
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, reload(user).getPassword()));
        assertNull(reload(user).getPasswordChangedAt());
    }

    @Test
    @DisplayName("A successful reset ends earlier sessions; failed attempts do not")
    public void sessionsFollowTheReset() throws Exception {
        User user = withCode(account("session", Role.MANAGER, true), CODE, 0);
        String session = loginToken(user, OLD_PASSWORD);

        assertEquals(400, confirm(P + "session@hotel.test", "000000", NEW_PASSWORD).status());
        assertEquals(400, confirm(P + "session@hotel.test", CODE, "weak").status());
        assertEquals(200, probe(session), "failed attempts leave the session alone");

        assertEquals(200, confirm(P + "session@hotel.test", CODE, NEW_PASSWORD).status());
        assertEquals(401, probe(session), "the reset ended it");
        assertEquals(200, probe(loginToken(user, NEW_PASSWORD)), "a new sign-in works at once");
    }

    @Test
    @DisplayName("Every role can request a reset, confirm it and sign in with the new password (and no longer with the old one)")
    public void everyRoleCanReset() throws Exception {
        for (Role role : Role.values()) {
            String name = "role_" + role.name().toLowerCase();
            User user = account(name, role, true);
            reset(emailService);
            when(emailService.isConfigured()).thenReturn(true);

            assertEquals(200, requestReset(P + name + "@hotel.test").status());
            String emailed = lastEmailedCode(P + name + "@hotel.test");
            assertEquals(200, confirm(P + name + "@hotel.test", emailed, NEW_PASSWORD).status(), role.name());

            assertNotNull(loginToken(user, NEW_PASSWORD), role + " signs in with the new password");
            MvcResult old = send(post("/api/auth/login"), "{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + OLD_PASSWORD + "\"}");
            assertEquals(401, old.getResponse().getStatus(), role + ": the old password is refused");
        }
    }
}
