package com.hotel.workload.security;

import com.hotel.workload.staff.StaffRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Every role (ADMIN, MANAGER, STAFF) must verify its email before it gets a session. Runs on the in-memory test database with a
 * mocked email service (no real email is sent), and removes the accounts it creates. Each test uses its own client address.
 */
@SpringBootTest
public class MandatoryEmailVerificationTest {

    private static final String PREFIX = "mev_";
    private static final String PASSWORD = "Correct@123";
    private static final Role[] ROLES = {Role.ADMIN, Role.MANAGER, Role.STAFF};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        staffRepository.findAllWithUser().stream().filter(s -> s.getUser().getUsername().startsWith(PREFIX)).forEach(staffRepository::delete);
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private User account(Role role, boolean verified) {
        User user = new User();
        user.setUsername(PREFIX + role.name().toLowerCase() + (verified ? "_ok" : "_new"));
        user.setEmail(user.getUsername() + "@hotel.test");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(verified);
        return userRepository.saveAndFlush(user);
    }

    private MvcResult postJson(String url, String client, String json) throws Exception {
        return send(post(url), client, json);
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String client, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr(client); return request; }).content(json)).andReturn();
    }

    private MvcResult login(String client, String username, String password) throws Exception {
        return postJson("/api/auth/login", client, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private static boolean hasToken(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString().contains("\"token\":\"");
    }

    private static void assertVerificationRequired(MvcResult result, Role role) throws Exception {
        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"emailVerificationRequired\":true"), body);
        assertTrue(body.contains("\"role\":\"" + role.name() + "\""), body);
        assertTrue(body.contains("verification code"), "tells the user a code was sent");
        assertTrue(body.contains("request a new code"), "and how to get another one");
        assertFalse(hasToken(result), "no token for an unverified account: " + body);
        assertFalse(body.contains(PASSWORD) || body.toLowerCase().contains("hash"));
    }

    // ── login ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An unverified ADMIN, MANAGER or STAFF account gets no token; a verification code is sent instead")
    void unverifiedAccountsAreBlockedForEveryRole() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            User user = account(role, false);
            MvcResult result = login("10.7.0." + (++n), user.getUsername(), PASSWORD);
            assertVerificationRequired(result, role);
            verify(emailService).sendVerificationEmail(eq(user.getEmail()), any());
        }
    }

    @Test
    @DisplayName("When the verification email cannot be sent, login fails safely for every role: no token, no code stored, no internal details")
    void failedEmailDeliveryNeverLetsAnyRoleIn() throws Exception {
        org.mockito.Mockito.doThrow(new com.hotel.workload.verification.EmailSendingException(
                        "Failed to send verification email via SendGrid, status code: 403"))
                .when(emailService).sendVerificationEmail(any(), any());
        int n = 0;
        for (Role role : ROLES) {
            User user = account(role, false);
            MvcResult result = login("10.7.5." + (++n), user.getUsername(), PASSWORD);
            String body = result.getResponse().getContentAsString();
            assertEquals(500, result.getResponse().getStatus(), body);
            assertTrue(body.contains("\"success\":false"), body);
            assertTrue(body.contains("could not send the email"), body);
            assertFalse(hasToken(result), body);
            assertFalse(body.toLowerCase().contains("sendgrid") || body.contains("403") || body.contains(PASSWORD), body);
            User reloaded = userRepository.findByUsername(user.getUsername()).orElseThrow();
            assertFalse(reloaded.isEmailVerified(), "still unverified");
            assertEquals(null, reloaded.getVerificationCodeHash(), "no code is stored when it could not be delivered");
        }
    }

    @Test
    @DisplayName("Verified accounts of all three roles can sign in with valid credentials and get a token")
    void verifiedAccountsCanSignIn() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            User user = account(role, true);
            MvcResult result = login("10.7.1." + (++n), user.getUsername(), PASSWORD);
            assertEquals(200, result.getResponse().getStatus());
            assertTrue(hasToken(result), role.name());
            assertTrue(result.getResponse().getContentAsString().contains("\"role\":\"" + role.name() + "\""));
        }
        verify(emailService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("Wrong credentials stay a plain 401 for unverified accounts too: no code is sent and nothing about verification is revealed")
    void incorrectCredentialsAreHandledSafely() throws Exception {
        User user = account(Role.MANAGER, false);
        MvcResult wrong = login("10.7.2.1", user.getUsername(), "Wrong@123");
        assertEquals(401, wrong.getResponse().getStatus());
        assertEquals(401, login("10.7.2.1", PREFIX + "nobody", "Wrong@123").getResponse().getStatus());
        assertFalse(wrong.getResponse().getContentAsString().contains("verif"));
        verify(emailService, never()).sendVerificationEmail(any(), any());
    }

    // ── creation paths ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Accounts created for every role start unverified and cannot sign in until verified")
    void createdAccountsStartUnverified() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            String username = PREFIX + "created_" + role.name().toLowerCase();
            String json = """
                    {"fullName": "Created %s", "email": "%s@hotel.test", "department": "FRONT_DESK", "availability": "AVAILABLE",
                     "username": "%s", "temporaryPassword": "TempPass@123", "role": "%s"}
                    """.formatted(role, username, username, role);
            MvcResult created = mockMvc.perform(post("/api/staff").with(user("creator").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
            assertEquals(201, created.getResponse().getStatus(), role + ": " + created.getResponse().getContentAsString());

            User stored = userRepository.findByUsername(username).orElseThrow();
            assertFalse(stored.isEmailVerified(), role + " must start unverified");
            assertEquals(role, stored.getRole());
            assertVerificationRequired(login("10.7.3." + (++n), username, "TempPass@123"), role);
        }
    }

    @Test
    @DisplayName("The stored state is never masked: an unverified ADMIN or MANAGER stays unverified when loaded and saved again")
    void storedStateIsNotOverwritten() {
        for (Role role : new Role[] {Role.ADMIN, Role.MANAGER}) {
            User user = account(role, false);
            User loaded = userRepository.findById(user.getId()).orElseThrow();
            assertFalse(loaded.isEmailVerified());
            loaded.setFirstLogin(false);
            userRepository.saveAndFlush(loaded);
            assertFalse(userRepository.findById(user.getId()).orElseThrow().isEmailVerified(), role.name());
        }
    }

    // ── after verification ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("After the emailed code is verified, login works for each role and role-based access is unchanged")
    void verificationThenLoginAndAuthorization() throws Exception {
        for (Role role : new Role[] {Role.ADMIN, Role.MANAGER}) {
            User user = account(role, false);
            String client = "10.7.4." + role.ordinal();
            assertVerificationRequired(login(client, user.getUsername(), PASSWORD), role);

            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendVerificationEmail(eq(user.getEmail()), code.capture());
            MvcResult verified = postJson("/api/auth/email-verification/verify", client,
                    "{\"identifier\":\"" + user.getUsername() + "\",\"code\":\"" + code.getValue() + "\"}");
            assertEquals(200, verified.getResponse().getStatus());
            assertFalse(hasToken(verified), "verifying a code never issues a session");
            assertTrue(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());

            MvcResult signedIn = login(client, user.getUsername(), PASSWORD);
            assertEquals(200, signedIn.getResponse().getStatus());
            String token = signedIn.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
            int admin = mockMvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
            assertEquals(role == Role.ADMIN ? 200 : 403, admin, role + " keeps exactly its own permissions");
            reset(emailService);
            when(emailService.isConfigured()).thenReturn(true);
        }
    }

    // ── bypass attempts ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("No alternate path gives an unverified account a session: wrong code, password reset and setup token all leave it blocked")
    void noAlternatePathBypassesVerification() throws Exception {
        User user = account(Role.MANAGER, false);
        String client = "10.7.5.1";

        // a wrong verification code verifies nothing and returns no token
        assertVerificationRequired(login(client, user.getUsername(), PASSWORD), Role.MANAGER);
        MvcResult wrongCode = postJson("/api/auth/email-verification/verify", client, "{\"identifier\":\"" + user.getUsername() + "\",\"code\":\"000000\"}");
        assertEquals(400, wrongCode.getResponse().getStatus());
        assertFalse(hasToken(wrongCode));
        assertFalse(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());

        // resetting the password does not verify the account or sign it in
        User stored = userRepository.findById(user.getId()).orElseThrow();
        stored.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        stored.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        userRepository.saveAndFlush(stored);
        MvcResult reset = postJson("/api/auth/password-reset/confirm", client,
                "{\"email\":\"" + user.getEmail() + "\",\"code\":\"123456\",\"newPassword\":\"Another@123\",\"confirmPassword\":\"Another@123\"}");
        assertEquals(200, reset.getResponse().getStatus());
        assertFalse(hasToken(reset));
        assertFalse(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertVerificationRequired(login(client, user.getUsername(), "Another@123"), Role.MANAGER);

        // credential setup needs a token that only a successful verification issues
        MvcResult setup = send(put("/api/auth/credentials/setup"), client,
                "{\"setupToken\":\"guessed-token\",\"newUsername\":\"" + PREFIX + "x\",\"newPassword\":\"Brand@New123\"}");
        assertEquals(400, setup.getResponse().getStatus());
        assertFalse(hasToken(setup));

        // and without a session the protected API stays closed
        assertEquals(401, mockMvc.perform(get("/api/staff")).andReturn().getResponse().getStatus());
    }

    // ── rate limits ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Login limits still apply to unverified accounts, and a verification code still dies after five wrong tries")
    void rateLimitsStillWork() throws Exception {
        User user = account(Role.ADMIN, false);
        String client = "10.7.6.1";
        for (int i = 0; i < 5; i++) assertEquals(401, login(client, user.getUsername(), "Wrong@123").getResponse().getStatus());
        MvcResult blocked = login(client, user.getUsername(), PASSWORD);
        assertEquals(429, blocked.getResponse().getStatus());
        assertNotNull(blocked.getResponse().getHeader("Retry-After"));
        assertFalse(hasToken(blocked));

        String other = "10.7.6.2";
        assertVerificationRequired(login(other, user.getUsername(), PASSWORD), Role.ADMIN);
        for (int i = 0; i < 5; i++) {
            assertEquals(400, postJson("/api/auth/email-verification/verify", other, "{\"identifier\":\"" + user.getUsername() + "\",\"code\":\"000000\"}").getResponse().getStatus());
        }
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(eq(user.getEmail()), code.capture());
        MvcResult afterLimit = postJson("/api/auth/email-verification/verify", other, "{\"identifier\":\"" + user.getUsername() + "\",\"code\":\"" + code.getValue() + "\"}");
        assertEquals(400, afterLimit.getResponse().getStatus(), "the code was invalidated, even the right one is refused");
        assertFalse(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());
    }
}
