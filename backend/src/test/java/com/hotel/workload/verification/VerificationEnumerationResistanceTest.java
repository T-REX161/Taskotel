package com.hotel.workload.verification;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Step 2J, stage 1: the public verification endpoints must not reveal whether a username or email belongs to an account.
 * Every outcome that depends on the account (unknown, inactive, no code, wrong, expired, used-up) must look the same to the caller,
 * the first-time verification and password-setup flow must still work for every role, and the hashing work must not depend on whether
 * the account exists. Every test rolls back.
 */
@SpringBootTest
@Transactional
public class VerificationEnumerationResistanceTest {

    private static final String CODE = "123456";
    private static final String NEW_PASSWORD = "Brand@New123";
    private static final String P = "venum_";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        // Start from an anonymous thread: another test class may have left a signed-in user in the security context
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User account(String name, Role role, boolean active, boolean verified, boolean setupPending) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(P + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("irrelevant"));
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(verified);
        user.setCredentialsSetupRequired(setupPending);
        return userRepository.saveAndFlush(user);
    }

    private User withCode(User user, int attempts, LocalDateTime expires) {
        user.setVerificationCodeHash(passwordEncoder.encode(CODE));
        user.setVerificationCodeExpiresAt(expires);
        user.setVerificationAttemptCount(attempts);
        return userRepository.saveAndFlush(user);
    }

    private record Answer(int status, String body) {}

    private Answer send(String identifier) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/email-verification/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\"}")).andReturn();
        return new Answer(r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    private Answer postVerify(String identifier, String code) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/email-verification/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"code\":\"" + code + "\"}")).andReturn();
        return new Answer(r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    // ── send ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send: a known account, an unknown username, an unknown email, an inactive account and a verified account all get the identical answer")
    public void sendAnswersAreIdentical() throws Exception {
        account("known", Role.STAFF, true, false, true);
        account("inactive", Role.STAFF, false, false, true);
        account("verified", Role.STAFF, true, true, false);

        Map<String, Answer> answers = new LinkedHashMap<>();
        for (String identifier : List.of(P + "known", P + "known@hotel.test", P + "nobody", P + "nobody@hotel.test", P + "inactive",
                P + "inactive@hotel.test", P + "verified")) {
            answers.put(identifier, send(identifier));
        }
        Answer first = answers.values().iterator().next();
        assertEquals(200, first.status());
        assertTrue(first.body().contains(EmailVerificationController.SEND_MESSAGE));
        answers.forEach((identifier, answer) -> {
            assertEquals(first, answer, "the answer for " + identifier + " differs");
            assertFalse(answer.body().contains(identifier), "the identifier must never be echoed");
            assertFalse(answer.body().toLowerCase().contains("not found"));
        });
    }

    @Test
    @DisplayName("send: only an active, known account is emailed, and an inactive account's state is left untouched")
    public void sendOnlyEmailsActiveKnownAccounts() throws Exception {
        account("known", Role.STAFF, true, false, true);
        User inactive = account("inactive", Role.STAFF, false, false, true);

        send(P + "nobody");
        send(P + "nobody@hotel.test");
        send(P + "inactive");
        send(P + "inactive@hotel.test");
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());

        send(P + "known");
        send(P + "known@hotel.test"); // inside the 60-second cooldown of the first: answered the same, nothing sent
        ArgumentCaptor<String> recipients = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendVerificationEmail(recipients.capture(), anyString());
        assertEquals(List.of(P + "known@hotel.test"), recipients.getAllValues());

        User reloaded = userRepository.findById(inactive.getId()).orElseThrow();
        assertNull(reloaded.getVerificationCodeHash(), "no code was stored for an inactive account");
        assertNull(reloaded.getVerificationCodeExpiresAt());
    }

    @Test
    @DisplayName("send: a blank identifier is a plain request error that says nothing about any account")
    public void sendBlankIdentifier() throws Exception {
        Answer blank = send("   ");
        assertEquals(400, blank.status());
        assertTrue(blank.body().contains("User identifier is required"));
    }

    // ── verify ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify: unknown, inactive, no code, wrong, expired and used-up all get the same 400 and the same body, with no attempt count")
    public void verifyFailuresAreIdentical() throws Exception {
        User inactive = account("inactive", Role.STAFF, false, false, true);
        withCode(inactive, 0, LocalDateTime.now().plusMinutes(10));
        account("nocode", Role.STAFF, true, false, true);
        withCode(account("wrong", Role.STAFF, true, false, true), 0, LocalDateTime.now().plusMinutes(10));
        withCode(account("expired", Role.STAFF, true, false, true), 0, LocalDateTime.now().minusMinutes(1));
        withCode(account("usedup", Role.STAFF, true, false, true), VerificationCodeServiceImpl.MAX_ATTEMPTS, LocalDateTime.now().plusMinutes(10));

        Map<String, Answer> answers = new LinkedHashMap<>();
        answers.put("unknown", postVerify(P + "nobody", CODE));
        answers.put("unknown by email", postVerify(P + "nobody@hotel.test", CODE));
        answers.put("inactive (correct code)", postVerify(P + "inactive", CODE));
        answers.put("known, no active code", postVerify(P + "nocode", CODE));
        answers.put("known, wrong code", postVerify(P + "wrong", "000000"));
        answers.put("known, expired code (correct)", postVerify(P + "expired", CODE));
        answers.put("known, used-up code (correct)", postVerify(P + "usedup", CODE));

        Answer expected = answers.get("unknown");
        assertEquals(400, expected.status());
        assertTrue(expected.body().contains(VerificationCodeServiceImpl.GENERIC_FAILURE));
        answers.forEach((label, answer) -> {
            assertEquals(expected, answer, label + " is distinguishable from an unknown account");
            String lower = answer.body().toLowerCase();
            assertFalse(lower.contains("remaining") || lower.contains("attempt") || lower.contains("no active") || lower.contains("not found"), label);
            assertFalse(answer.body().contains(P), label + ": the identifier must not be echoed");
        });
    }

    @Test
    @DisplayName("verify: a wrong code still counts as an attempt and the fifth invalidates the code, but the answer never says how many are left")
    public void verifyAttemptMechanicsAreUnchangedButSilent() throws Exception {
        User user = withCode(account("counting", Role.STAFF, true, false, true), 0, LocalDateTime.now().plusMinutes(10));
        Answer firstAnswer = null;
        for (int attempt = 1; attempt <= VerificationCodeServiceImpl.MAX_ATTEMPTS; attempt++) {
            Answer answer = postVerify(P + "counting", "000000");
            if (firstAnswer == null) firstAnswer = answer;
            assertEquals(firstAnswer, answer, "attempt " + attempt + " must look like every other");
            User reloaded = userRepository.findById(user.getId()).orElseThrow();
            assertEquals(attempt, reloaded.getVerificationAttemptCount());
        }
        assertNull(userRepository.findById(user.getId()).orElseThrow().getVerificationCodeHash(), "the fifth wrong code invalidates the code");
        // Even the correct code is refused now, with the same answer
        assertEquals(firstAnswer, postVerify(P + "counting", CODE));
    }

    @Test
    @DisplayName("first-time flow for Admin, Manager and Staff: the right code still verifies, returns a setup token, and the password can be set")
    public void firstTimeFlowStillWorksForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            String name = "flow_" + role.name().toLowerCase();
            withCode(account(name, role, true, false, true), 0, LocalDateTime.now().plusMinutes(10));

            MvcResult verified = mockMvc.perform(post("/api/auth/email-verification/verify").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"identifier\":\"" + P + name + "\",\"code\":\"" + CODE + "\"}")).andReturn();
            assertEquals(200, verified.getResponse().getStatus(), role.name());
            String body = verified.getResponse().getContentAsString();
            assertTrue(body.contains("\"success\":true"), body);
            String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("setupToken").asText();
            assertFalse(token.isBlank(), role + " receives a setup token");

            MvcResult setup = mockMvc.perform(put("/api/auth/credentials/setup").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"setupToken\":\"" + token + "\",\"newUsername\":\"" + P + name + "\",\"newPassword\":\"" + NEW_PASSWORD
                            + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}")).andReturn();
            assertEquals(200, setup.getResponse().getStatus(), role + ": " + setup.getResponse().getContentAsString());
            assertTrue(setup.getResponse().getContentAsString().contains("\"token\""), role + " gets a session after setting the password");
        }
    }

    @Test
    @DisplayName("verify: an already-set-up account that verifies again succeeds without a setup token")
    public void verifiedAccountGetsNoSetupToken() throws Exception {
        withCode(account("plain", Role.STAFF, true, true, false), 0, LocalDateTime.now().plusMinutes(10));
        Answer answer = postVerify(P + "plain", CODE);
        assertEquals(200, answer.status());
        assertTrue(answer.body().contains("\"success\":true"));
        assertTrue(answer.body().contains("\"setupToken\":null"));
    }

    // ── equal hashing work ───────────────────────────────────────────────────

    /** The service with a real bcrypt encoder that counts its calls, and a repository stub that returns the given account. */
    private record Counting(VerificationCodeServiceImpl service, BCryptPasswordEncoder encoder, UserRepository repository, EmailService email) {}

    private Counting counting(User user) {
        BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder());
        UserRepository repository = mock(UserRepository.class);
        EmailService email = mock(EmailService.class);
        VerificationCodeServiceImpl service = new VerificationCodeServiceImpl(repository, encoder, email);
        if (user != null) {
            when(repository.findByUsernameForUpdate(user.getUsername())).thenReturn(Optional.of(user));
        }
        clearInvocations(encoder); // the constructor's one-off hash is not part of a request
        return new Counting(service, encoder, repository, email);
    }

    private User bareUser(boolean active, String hash, int attempts, LocalDateTime expires) {
        User user = new User();
        user.setUsername("someone");
        user.setEmail("someone@hotel.test");
        user.setRole(Role.STAFF);
        user.setActive(active);
        user.setVerificationCodeHash(hash);
        user.setVerificationAttemptCount(attempts);
        user.setVerificationCodeExpiresAt(expires);
        return user;
    }

    @Test
    @DisplayName("verify does exactly one bcrypt comparison whether the account is unknown, inactive, without a code, wrong, expired or used-up")
    public void verifyDoesTheSameHashingWorkForEveryCase() {
        String hash = new BCryptPasswordEncoder().encode(CODE);
        Map<String, User> cases = new LinkedHashMap<>();
        cases.put("unknown", null);
        cases.put("inactive", bareUser(false, hash, 0, LocalDateTime.now().plusMinutes(10)));
        cases.put("no code", bareUser(true, null, 0, null));
        cases.put("wrong code", bareUser(true, hash, 0, LocalDateTime.now().plusMinutes(10)));
        cases.put("expired", bareUser(true, hash, 0, LocalDateTime.now().minusMinutes(1)));
        cases.put("used up", bareUser(true, hash, VerificationCodeServiceImpl.MAX_ATTEMPTS, LocalDateTime.now().plusMinutes(10)));

        List<String> problems = new ArrayList<>();
        cases.forEach((label, user) -> {
            Counting c = counting(user);
            assertThrows(VerificationException.class, () -> c.service().verifyCode("someone", "000000"));
            try {
                verify(c.encoder(), times(1)).matches(anyString(), any());
            } catch (AssertionError e) {
                problems.add(label + ": " + e.getMessage());
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("send hashes once for an unknown or inactive account (as a real send does) and sends no email")
    public void sendDoesTheSameHashingWorkAndSendsNothingForUnknownAccounts() {
        Counting unknown = counting(null);
        unknown.service().sendVerificationCode("someone");
        verify(unknown.encoder(), times(1)).encode(anyString());
        verifyNoInteractions(unknown.email());
        verify(unknown.repository(), never()).save(any());

        Counting inactive = counting(bareUser(false, null, 0, null));
        inactive.service().sendVerificationCode("someone");
        verify(inactive.encoder(), times(1)).encode(anyString());
        verifyNoInteractions(inactive.email());
        verify(inactive.repository(), never()).save(any());

        Counting known = counting(bareUser(true, null, 0, null));
        known.service().sendVerificationCode("someone");
        verify(known.encoder(), times(1)).encode(anyString());
        verify(known.email(), times(1)).sendVerificationEmail(eq("someone@hotel.test"), anyString());
        verify(known.repository(), times(1)).save(any());
    }

    // ── a malformed code does not change the hashing work ─────────────────────

    @Test
    @DisplayName("verify does one bcrypt comparison even when the submitted code is null or malformed, for unknown, inactive, code-less and real accounts alike")
    public void malformedCodesStillCostOneComparison() {
        String hash = new BCryptPasswordEncoder().encode(CODE);
        Map<String, User> accounts = new LinkedHashMap<>();
        accounts.put("unknown", null);
        accounts.put("inactive", bareUser(false, hash, 0, LocalDateTime.now().plusMinutes(10)));
        accounts.put("no code", bareUser(true, null, 0, null));
        accounts.put("real", bareUser(true, hash, 0, LocalDateTime.now().plusMinutes(10)));

        List<String> problems = new ArrayList<>();
        for (String code : new String[]{null, "", "abc", "12345", "1234567", "12 456", "١٢٣٤٥٦"}) {
            accounts.forEach((label, user) -> {
                Counting c = counting(user == null ? null : bareUser(user.isActive(), user.getVerificationCodeHash(),
                        user.getVerificationAttemptCount(), user.getVerificationCodeExpiresAt()));
                assertThrows(VerificationException.class, () -> c.service().verifyCode("someone", code));
                try {
                    verify(c.encoder(), times(1)).matches(any(), any());
                } catch (AssertionError e) {
                    problems.add("code=" + code + ", " + label + ": " + e.getMessage());
                }
            });
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("verify never accepts a malformed code, even though the comparison is made: the placeholder result is ignored")
    public void malformedCodeIsNeverAccepted() {
        // The stored hash is of the placeholder itself: a malformed code must still fail
        User user = bareUser(true, new BCryptPasswordEncoder().encode("000000"), 0, LocalDateTime.now().plusMinutes(10));
        Counting c = counting(user);
        for (String code : new String[]{null, "", "abc", "00000", "0000000"}) {
            assertThrows(VerificationException.class, () -> c.service().verifyCode("someone", code));
        }
        assertFalse(user.isEmailVerified());
    }

    // ── delivery failure must not reveal that the account exists ─────────────

    @Test
    @DisplayName("send: when SendGrid fails for a known account the public answer is identical to a send for an unknown account")
    public void deliveryFailureLooksLikeAnUnknownAccount() throws Exception {
        account("known", Role.STAFF, true, false, true);
        doThrow(new EmailSendingException("SendGrid rejected request")).when(emailService).sendVerificationEmail(anyString(), anyString());

        Answer known = send(P + "known");
        Answer knownByEmail = send(P + "known@hotel.test");
        Answer unknown = send(P + "nobody");

        assertEquals(200, unknown.status());
        assertEquals(unknown, known, "a failed delivery for a real account must not differ from an unknown account");
        assertEquals(unknown, knownByEmail);
        String lower = known.body().toLowerCase();
        assertFalse(lower.contains("sendgrid") || lower.contains("fail") || lower.contains("error") || lower.contains("could not"), known.body());
        verify(emailService, atLeastOnce()).sendVerificationEmail(anyString(), anyString()); // it really was attempted
    }

    @Test
    @DisplayName("send: any exception from the email client (not only EmailSendingException) gets the same generic answer")
    public void anyClientFailureLooksTheSame() throws Exception {
        User known = account("known", Role.STAFF, true, false, true);
        Answer unknown = send(P + "nobody");
        for (RuntimeException failure : List.of(new IllegalStateException("socket closed"), new NullPointerException(), new EmailSendingException("timeout"))) {
            reset(emailService);
            known.setVerificationLastSentAt(null); // each failure is tried outside the cooldown the previous attempt reserved
            userRepository.saveAndFlush(known);
            doThrow(failure).when(emailService).sendVerificationEmail(anyString(), anyString());
            assertEquals(unknown, send(P + "known"), failure.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("send: a failed delivery stores no code when there was none, and leaves an earlier valid code and its attempt count untouched")
    public void deliveryFailureLeavesStateConsistent() throws Exception {
        User fresh = account("fresh", Role.STAFF, true, false, true);
        User existing = withCode(account("existing", Role.STAFF, true, false, true), 2, LocalDateTime.now().plusMinutes(8));
        String originalHash = existing.getVerificationCodeHash();
        doThrow(new EmailSendingException("down")).when(emailService).sendVerificationEmail(anyString(), anyString());

        send(P + "fresh");
        send(P + "existing");

        User freshAfter = userRepository.findById(fresh.getId()).orElseThrow();
        assertNull(freshAfter.getVerificationCodeHash(), "an undelivered code must not be left active");
        assertNull(freshAfter.getVerificationCodeExpiresAt());
        User existingAfter = userRepository.findById(existing.getId()).orElseThrow();
        assertEquals(originalHash, existingAfter.getVerificationCodeHash(), "the earlier valid code is not invalidated by a failed resend");
        assertEquals(2, existingAfter.getVerificationAttemptCount(), "a failed resend does not reset the attempts");
        // ...and the earlier code still works
        assertEquals(200, postVerify(P + "existing", CODE).status());
    }
}
