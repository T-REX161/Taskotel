package com.hotel.workload.security;

import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADMIN, MANAGER and STAFF must behave identically for first-time email verification: created through the real endpoint, the first
 * sign-in sends one code to the account's own address, the code is stored (hashed, expiring in 10 minutes) only after the email was
 * handed over, resending replaces it, an expired code is refused, and a correct code leads to password setup. The email service is
 * mocked: these tests prove what the application asks the email service to send, not that a message reached an inbox.
 */
@SpringBootTest
public class VerificationRoleParityTest {

    private static final String PREFIX = "vrp_";
    private static final Role[] ROLES = {Role.ADMIN, Role.MANAGER, Role.STAFF};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private int clients = 0;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(emailService);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        staffRepository.findAllWithUser().stream().filter(s -> s.getUser().getUsername().startsWith(PREFIX)).forEach(staffRepository::delete);
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private MvcResult call(String url, String json) throws Exception {
        String client = "10.8.8." + (++clients % 250);
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr(client); return r; }).content(json)).andReturn();
    }

    /** Creates the account the way the Users page does: POST /api/staff by an administrator. */
    private User create(Role role) throws Exception {
        String username = PREFIX + role.name().toLowerCase();
        String json = "{\"fullName\":\"Parity " + role + "\",\"username\":\"" + username + "\",\"email\":\"" + username + "@hotel.test\","
                + "\"phone\":\"555-0100\",\"department\":\"HOUSEKEEPING\",\"availability\":\"AVAILABLE\",\"role\":\"" + role + "\",\"temporaryPassword\":\"Temp@1234\"}";
        MvcResult created = mockMvc.perform(post("/api/staff").with(user("vrp_creator").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertEquals(201, created.getResponse().getStatus(), role + ": " + created.getResponse().getContentAsString());
        return userRepository.findByUsername(username).orElseThrow();
    }

    private String signInAndCaptureCode(User user) throws Exception {
        MvcResult login = call("/api/auth/login", "{\"username\":\"" + user.getUsername() + "\",\"password\":\"Temp@1234\"}");
        assertEquals(200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        assertTrue(login.getResponse().getContentAsString().contains("\"emailVerificationRequired\":true"));
        return lastCodeSentTo(user.getEmail());
    }

    private String lastCodeSentTo(String email) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.atLeastOnce()).sendVerificationEmail(to.capture(), code.capture());
        int last = to.getAllValues().size() - 1;
        assertEquals(email, to.getAllValues().get(last), "the code goes to the account's own address");
        return code.getAllValues().get(last);
    }

    // ── creation and first sign-in ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every role is created unverified with setup pending; first sign-in sends one six-digit code to the account's own address and stores only its hash, expiring in 10 minutes")
    void sameFlowForEveryRole() throws Exception {
        for (Role role : ROLES) {
            reset(emailService);
            User created = create(role);
            assertEquals(role, created.getRole());
            assertTrue(created.isActive() && !created.isEmailVerified() && created.isCredentialsSetupRequired(), role + " starts unverified with setup pending");
            assertNull(created.getVerificationCodeHash(), "creating an account sends nothing; the code is sent at first sign-in");

            LocalDateTime before = LocalDateTime.now();
            String code = signInAndCaptureCode(created);

            verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString());
            assertTrue(code.matches("\\d{6}"), role + " gets a six-digit code");
            User stored = userRepository.findById(created.getId()).orElseThrow();
            assertNotNull(stored.getVerificationCodeHash());
            assertNotEquals(code, stored.getVerificationCodeHash(), "only a hash is stored");
            assertTrue(passwordEncoder.matches(code, stored.getVerificationCodeHash()));
            assertEquals(0, stored.getVerificationAttemptCount());
            Duration lifetime = Duration.between(stored.getVerificationCreatedAt(), stored.getVerificationCodeExpiresAt());
            assertEquals(10, lifetime.toMinutes(), role + " code lifetime");
            assertFalse(stored.getVerificationCreatedAt().isBefore(before.minusSeconds(1)));
        }
    }

    @Test
    @DisplayName("A code is stored only after the email was handed over: if sending fails, no code is stored, for every role including ADMIN")
    void codeIsNotStoredWhenSendingFails() throws Exception {
        for (Role role : ROLES) {
            User created = create(role);
            doThrow(new EmailSendingException("boom")).when(emailService).sendVerificationEmail(anyString(), anyString());
            MvcResult login = call("/api/auth/login", "{\"username\":\"" + created.getUsername() + "\",\"password\":\"Temp@1234\"}");
            assertEquals(500, login.getResponse().getStatus(), role.name());
            assertFalse(login.getResponse().getContentAsString().contains("\"token\""));
            assertNull(userRepository.findById(created.getId()).orElseThrow().getVerificationCodeHash(), role + ": nothing stored");
            reset(emailService);
        }
    }

    // ── resend, expiry, verification ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Resending a code works for every role: a new code goes to the same address, replaces the old one and resets the attempts")
    void resendWorksForEveryRole() throws Exception {
        for (Role role : ROLES) {
            reset(emailService);
            User created = create(role);
            String first = signInAndCaptureCode(created);
            call("/api/auth/email-verification/verify", "{\"identifier\":\"" + created.getUsername() + "\",\"code\":\"" + (first.equals("000000") ? "111111" : "000000") + "\"}");
            assertEquals(1, userRepository.findById(created.getId()).orElseThrow().getVerificationAttemptCount(), "one wrong try counted");

            MvcResult resend = call("/api/auth/email-verification/send", "{\"identifier\":\"" + created.getUsername() + "\"}");
            assertEquals(200, resend.getResponse().getStatus(), role + ": " + resend.getResponse().getContentAsString());
            verify(emailService, times(2)).sendVerificationEmail(anyString(), anyString());
            String second = lastCodeSentTo(created.getEmail());

            User stored = userRepository.findById(created.getId()).orElseThrow();
            assertEquals(0, stored.getVerificationAttemptCount(), "attempts reset");
            assertTrue(passwordEncoder.matches(second, stored.getVerificationCodeHash()), "the new code is the stored one");
            if (!first.equals(second)) {
                MvcResult old = call("/api/auth/email-verification/verify", "{\"identifier\":\"" + created.getUsername() + "\",\"code\":\"" + first + "\"}");
                assertEquals(400, old.getResponse().getStatus(), role + ": the replaced code no longer works");
            }
        }
    }

    @Test
    @DisplayName("An expired code is refused and cleared for every role, and a resend gives a working one")
    void expiredCodeForEveryRole() throws Exception {
        for (Role role : ROLES) {
            reset(emailService);
            User created = create(role);
            String code = signInAndCaptureCode(created);
            User stored = userRepository.findById(created.getId()).orElseThrow();
            stored.setVerificationCodeExpiresAt(LocalDateTime.now().minusMinutes(1));
            userRepository.saveAndFlush(stored);

            MvcResult expired = call("/api/auth/email-verification/verify", "{\"identifier\":\"" + created.getUsername() + "\",\"code\":\"" + code + "\"}");
            assertEquals(400, expired.getResponse().getStatus(), role.name());
            assertTrue(expired.getResponse().getContentAsString().contains("expired"), expired.getResponse().getContentAsString());
            assertNull(userRepository.findById(created.getId()).orElseThrow().getVerificationCodeHash());
            assertFalse(userRepository.findById(created.getId()).orElseThrow().isEmailVerified());

            call("/api/auth/email-verification/send", "{\"identifier\":\"" + created.getUsername() + "\"}");
            String fresh = lastCodeSentTo(created.getEmail());
            MvcResult ok = call("/api/auth/email-verification/verify", "{\"identifier\":\"" + created.getUsername() + "\",\"code\":\"" + fresh + "\"}");
            assertEquals(200, ok.getResponse().getStatus(), role + ": " + ok.getResponse().getContentAsString());
        }
    }

    @Test
    @DisplayName("A correct code verifies the account and returns a setup token for every role, which leads to password setup; no session is given before that")
    void verificationLeadsToPasswordSetupForEveryRole() throws Exception {
        for (Role role : ROLES) {
            reset(emailService);
            User created = create(role);
            String code = signInAndCaptureCode(created);

            MvcResult verified = call("/api/auth/email-verification/verify", "{\"identifier\":\"" + created.getUsername() + "\",\"code\":\"" + code + "\"}");
            String body = verified.getResponse().getContentAsString();
            assertEquals(200, verified.getResponse().getStatus(), body);
            assertTrue(body.contains("\"setupToken\":\""), role + " is sent on to password setup: " + body);
            assertFalse(body.contains("\"token\":\""), "verification alone gives no session");
            User stored = userRepository.findById(created.getId()).orElseThrow();
            assertTrue(stored.isEmailVerified() && stored.isCredentialsSetupRequired(), "verified, but must still set a password");
            assertNull(stored.getVerificationCodeHash(), "the used code is gone");
            assertNotNull(stored.getSetupToken());

            MvcResult setup = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/auth/credentials/setup")
                    .contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr("10.9.9." + (++clients % 250)); return r; })
                    .content("{\"setupToken\":\"" + stored.getSetupToken() + "\",\"newUsername\":\"" + created.getUsername() + "\",\"newPassword\":\"Brand#New9pw\",\"confirmPassword\":\"Brand#New9pw\"}")).andReturn();
            assertEquals(200, setup.getResponse().getStatus(), role + ": " + setup.getResponse().getContentAsString());
            assertFalse(userRepository.findById(created.getId()).orElseThrow().isCredentialsSetupRequired());
        }
    }
}
