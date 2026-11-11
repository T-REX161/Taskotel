package com.hotel.workload.verification;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * K04: the first-time verification flow is only for accounts that still have onboarding to do. These tests go through the real controller,
 * limiters, service and database.
 */
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = {
        "auth.verification-send-rate-limit.enabled=true",
        "auth.verification-send-rate-limit.window-seconds=600",
        "auth.verification-send-rate-limit.max-attempts-per-client-and-account=2",
        "auth.verification-send-rate-limit.max-attempts-per-client=50",
        "auth.verification-send-rate-limit.max-attempts-per-account=100",
        "auth.verification-verify-rate-limit.enabled=true",
        "auth.verification-verify-rate-limit.window-seconds=600",
        "auth.verification-verify-rate-limit.max-attempts-per-client-and-account=5",
        "auth.verification-verify-rate-limit.max-attempts-per-client=50",
        "auth.verification-verify-rate-limit.max-attempts-per-account=100"
})
public class VerificationEligibilityTest {

    private static final String P = "vel_";
    private static final String CODE = "482910";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private static int clients = 0;

    @BeforeEach
    public void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
    }

    @AfterEach
    public void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(P)).forEach(userRepository::delete);
        userRepository.flush();
    }

    private static String freshClient() {
        return "10.88." + ((++clients / 250) % 250) + "." + (clients % 250 + 1);
    }

    /** verified: email verified; setupRequired: the first password is still to be set. */
    private User account(String name, Role role, boolean verified, boolean setupRequired) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(P + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("irrelevant"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(verified);
        user.setCredentialsSetupRequired(setupRequired);
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        userRepository.flush();
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MvcResult send(String identifier) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\"}").with(r -> { r.setRemoteAddr(freshClient()); return r; })).andReturn();
    }

    private MvcResult verifyCode(String identifier, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"code\":\"" + code + "\"}").with(r -> { r.setRemoteAddr(freshClient()); return r; })).andReturn();
    }

    /** Every verification-related column of an account as one string, so "exactly unchanged" is one comparison. */
    private String verificationState(User user) {
        User u = reload(user);
        return "verified=" + u.isEmailVerified() + ";setup=" + u.isCredentialsSetupRequired() + ";hash=" + u.getVerificationCodeHash()
                + ";expires=" + u.getVerificationCodeExpiresAt() + ";created=" + u.getVerificationCreatedAt() + ";lastSent=" + u.getVerificationLastSentAt()
                + ";attempts=" + u.getVerificationAttemptCount() + ";lockedUntil=" + u.getVerificationLockedUntil() + ";token=" + u.getSetupToken();
    }

    private static final Role[] ROLES = {Role.ADMIN, Role.MANAGER, Role.STAFF};

    // ── finished accounts ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A verified, setup-complete account (any role) that asks for a code: the usual answer, no email, no state change at all")
    void finishedAccountGetsNoCode() throws Exception {
        for (Role role : ROLES) {
            User user = account("done_" + role, role, true, false);
            String before = verificationState(user);
            MvcResult byName = send(user.getUsername());
            MvcResult byEmail = send(user.getEmail());
            assertEquals(200, byName.getResponse().getStatus(), role.name());
            assertEquals(200, byEmail.getResponse().getStatus(), role.name());
            assertTrue(byName.getResponse().getContentAsString().contains(EmailVerificationController.SEND_MESSAGE), role.name());
            assertEquals(before, verificationState(user), role + ": nothing stored, not even the cooldown");
            assertNull(reload(user).getVerificationLastSentAt());
        }
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("The answer for a finished account is the same as for an unknown one and for a pending one (no enumeration)")
    void answersAreIndistinguishable() throws Exception {
        User done = account("same_done", Role.STAFF, true, false);
        account("same_pending", Role.STAFF, false, true);
        String finished = send(done.getUsername()).getResponse().getContentAsString();
        String pending = send(P + "same_pending").getResponse().getContentAsString();
        String unknown = send(P + "no_such_account").getResponse().getContentAsString();
        assertEquals(finished, pending);
        assertEquals(finished, unknown);
    }

    @Test
    @DisplayName("Wrong codes against a finished account are the generic failure, are not counted, and can never lock it")
    void finishedAccountCannotBeLockedOrCounted() throws Exception {
        for (Role role : ROLES) {
            User user = account("lock_" + role, role, true, false);
            // even if a code were somehow on file (it should not be), guesses must not touch the account
            user.setVerificationCodeHash(passwordEncoder.encode(CODE));
            user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
            userRepository.saveAndFlush(user);
            String before = verificationState(user);
            for (int i = 0; i < 12; i++) {
                MvcResult wrong = verifyCode(user.getUsername(), "000000");
                assertEquals(400, wrong.getResponse().getStatus(), role.name());
                assertTrue(wrong.getResponse().getContentAsString().contains(VerificationCodeServiceImpl.GENERIC_FAILURE), role.name());
            }
            MvcResult right = verifyCode(user.getUsername(), CODE);
            assertEquals(400, right.getResponse().getStatus(), role + ": even the right code does nothing for a finished account");
            assertEquals(before, verificationState(user), role + ": no attempt counted, no lock, nothing cleared");
            assertEquals(0, reload(user).getVerificationAttemptCount());
            assertNull(reload(user).getVerificationLockedUntil());
        }
    }

    @Test
    @DisplayName("Repeated requests for a finished account never change its state or reach the email service, however many are made")
    void repeatedRequestsChangeNothing() throws Exception {
        User user = account("repeat", Role.MANAGER, true, false);
        String before = verificationState(user);
        for (int i = 0; i < 25; i++) send(user.getUsername());
        assertEquals(before, verificationState(user));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("A finished account's existing state (an old lock, cooldown, attempt count) is left exactly as it is by a request")
    void existingStateIsPreserved() throws Exception {
        User user = account("keep", Role.STAFF, true, false);
        user.setVerificationAttemptCount(3);
        user.setVerificationLastSentAt(LocalDateTime.now().minusDays(2));
        user.setVerificationLockedUntil(LocalDateTime.now().minusDays(1)); // a lock that has run out: it would normally be lifted
        userRepository.saveAndFlush(user);
        String before = verificationState(user);
        send(user.getUsername());
        verifyCode(user.getUsername(), "111111");
        assertEquals(before, verificationState(user));
    }

    // ── accounts that still have onboarding to do ───────────────────────────────────────────

    @Test
    @DisplayName("An account that is not yet verified can request a code and verify it (every role), and gets a setup token")
    void unverifiedAccountCompletesOnboarding() throws Exception {
        for (Role role : ROLES) {
            User user = account("new_" + role, role, false, true);
            assertEquals(200, send(user.getUsername()).getResponse().getStatus(), role.name());
            User sent = reload(user);
            assertNotNull(sent.getVerificationCodeHash(), role + ": a code was stored");
            assertNotNull(sent.getVerificationLastSentAt(), role + ": and the cooldown reserved");

            // the real code was handed to the email service: capture it and use it
            org.mockito.ArgumentCaptor<String> code = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(emailService, org.mockito.Mockito.atLeastOnce()).sendVerificationEmail(org.mockito.ArgumentMatchers.eq(user.getEmail()), code.capture());
            MvcResult ok = verifyCode(user.getUsername(), code.getValue());
            assertEquals(200, ok.getResponse().getStatus(), role + ": " + ok.getResponse().getContentAsString());
            User done = reload(user);
            assertTrue(done.isEmailVerified(), role.name());
            assertNotNull(done.getSetupToken(), role + ": the setup token is issued");
        }
    }

    @Test
    @DisplayName("A verified account that still has to set its first password can still request a new code and verify again (the login path)")
    void verifiedButSetupPendingStillWorks() throws Exception {
        for (Role role : ROLES) {
            User user = account("setup_" + role, role, true, true);
            assertEquals(200, send(user.getUsername()).getResponse().getStatus(), role.name());
            assertNotNull(reload(user).getVerificationCodeHash(), role + ": a code was stored for the setup-pending account");
            org.mockito.ArgumentCaptor<String> code = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(emailService, org.mockito.Mockito.atLeastOnce()).sendVerificationEmail(org.mockito.ArgumentMatchers.eq(user.getEmail()), code.capture());
            MvcResult ok = verifyCode(user.getUsername(), code.getValue());
            assertEquals(200, ok.getResponse().getStatus(), role.name());
            assertNotNull(reload(user).getSetupToken(), role.name());
        }
    }

    @Test
    @DisplayName("For a pending account the existing protections are unchanged: the cooldown suppresses a second send, and five wrong codes still lock")
    void pendingAccountProtectionsStay() throws Exception {
        User user = account("prot", Role.STAFF, false, true);
        send(user.getUsername());
        String afterFirst = verificationState(user);
        send(user.getUsername()); // inside the 60-second cooldown: suppressed
        assertEquals(afterFirst, verificationState(user), "the second request inside the cooldown changed nothing");
        verify(emailService, org.mockito.Mockito.times(1)).sendVerificationEmail(anyString(), anyString());

        for (int i = 0; i < 5; i++) verifyCode(user.getUsername(), "000000");
        User locked = reload(user);
        assertNotNull(locked.getVerificationLockedUntil(), "the fifth wrong code locks, as before");
        assertNull(locked.getVerificationCodeHash());
    }

    @Test
    @DisplayName("Deactivated accounts are still treated as unknown (unchanged)")
    void deactivatedStillIgnored() throws Exception {
        User user = account("off", Role.STAFF, false, true);
        user.setActive(false);
        userRepository.saveAndFlush(user);
        String before = verificationState(user);
        send(user.getUsername());
        assertEquals(before, verificationState(user));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("A request for a finished account still counts against the client's send limit, so it cannot be used to flood for free")
    void requestsStillCharged() throws Exception {
        User user = account("charged", Role.STAFF, true, false);
        String client = freshClient();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            statuses.add(mockMvc.perform(post("/api/auth/email-verification/send").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"identifier\":\"" + user.getUsername() + "\"}").with(r -> { r.setRemoteAddr(client); return r; })).andReturn().getResponse().getStatus());
        }
        assertEquals(List.of(200, 200, 429, 429, 429, 429), statuses, "2 per client and account (as configured here), then refused: the limit applies whatever the account's state");
    }
}
