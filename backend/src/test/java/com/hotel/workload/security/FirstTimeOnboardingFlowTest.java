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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The first-time onboarding sequence is the same for ADMIN, MANAGER and STAFF: verify the email with the emailed code, set the
 * first password with the one-time setup token, then sign in. Runs on the in-memory test database with a mocked email service.
 */
@SpringBootTest
public class FirstTimeOnboardingFlowTest {

    private static final String PREFIX = "onb_";
    private static final String TEMP_PASSWORD = "Temp@1234";
    private static final String NEW_PASSWORD = "Brand@New123";
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

    /** An account exactly as the create-account endpoint leaves it: unverified, with the first password still to be set. */
    private User newAccount(Role role) {
        User user = new User();
        user.setUsername(PREFIX + role.name().toLowerCase());
        user.setEmail(user.getUsername() + "@hotel.test");
        user.setPassword(passwordEncoder.encode(TEMP_PASSWORD));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setCredentialsSetupRequired(true);
        return userRepository.saveAndFlush(user);
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String client, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr(client); return request; }).content(json)).andReturn();
    }

    private MvcResult login(String client, String username, String password) throws Exception {
        return send(post("/api/auth/login"), client, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private MvcResult verifyEmail(String client, String username, String code) throws Exception {
        return send(post("/api/auth/email-verification/verify"), client, "{\"identifier\":\"" + username + "\",\"code\":\"" + code + "\"}");
    }

    private MvcResult setup(String client, String setupToken, String username, String password) throws Exception {
        return send(put("/api/auth/credentials/setup"), client, "{\"setupToken\":\"" + setupToken + "\",\"newUsername\":\"" + username
                + "\",\"newPassword\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}");
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static boolean hasSession(MvcResult result) throws Exception {
        return body(result).contains("\"token\":\"");
    }

    private static String field(MvcResult result, String name) throws Exception {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(body(result));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The code the account was just emailed. */
    private String emailedCode(User user) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendVerificationEmail(eq(user.getEmail()), code.capture());
        return code.getValue();
    }

    private int adminApi(String token) throws Exception {
        return mockMvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    private int managerApi(String token) throws Exception {
        return mockMvc.perform(get("/api/test/manager").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    // ── the full sequence ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("New ADMIN, MANAGER and STAFF: login sends a code → verification returns a setup token → password setup → login with the new password")
    void everyRoleFollowsTheSameOnboardingSequence() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            User user = newAccount(role);
            String client = "10.8.0." + (++n);

            // 1-2. valid temporary credentials: no session, a code is emailed
            MvcResult first = login(client, user.getUsername(), TEMP_PASSWORD);
            assertTrue(body(first).contains("\"emailVerificationRequired\":true"), body(first));
            assertFalse(hasSession(first), role.name());
            String code = emailedCode(user);

            // 3-4. verification succeeds and hands over the one-time setup token that leads to password setup
            MvcResult verified = verifyEmail(client, user.getUsername(), code);
            assertEquals(200, verified.getResponse().getStatus(), body(verified));
            String setupToken = field(verified, "setupToken");
            assertNotNull(setupToken, role + ": verification must lead to password setup");
            assertFalse(hasSession(verified), "verification alone gives no session");
            assertTrue(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());

            // the account cannot sign in until the password is set, and the setup token is not a login token
            assertFalse(hasSession(login(client, user.getUsername(), TEMP_PASSWORD)), role + " must not sign in before setup");
            assertEquals(401, mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + setupToken)).andReturn().getResponse().getStatus());

            // 5. the password is set (existing validation applies) and the setup token cannot be used again
            MvcResult done = setup(client, setupToken, user.getUsername(), NEW_PASSWORD);
            assertEquals(200, done.getResponse().getStatus(), body(done));
            assertEquals(role.name(), field(done, "role"));
            assertEquals(400, setup(client, setupToken, user.getUsername(), "Another@Pass123").getResponse().getStatus(), "reused token");
            User stored = userRepository.findById(user.getId()).orElseThrow();
            assertFalse(stored.isCredentialsSetupRequired());
            assertNull(stored.getSetupToken());
            assertTrue(passwordEncoder.matches(NEW_PASSWORD, stored.getPassword()));
            assertFalse(NEW_PASSWORD.equals(stored.getPassword()), "stored hashed");

            // 6-7. login with the new password; the old one is gone; the role decides what the session may reach
            assertEquals(401, login(client, user.getUsername(), TEMP_PASSWORD).getResponse().getStatus());
            MvcResult in = login(client, user.getUsername(), NEW_PASSWORD);
            assertEquals(200, in.getResponse().getStatus());
            assertEquals(role.name(), field(in, "role"));
            String token = field(in, "token");
            assertNotNull(token);
            assertEquals(role == Role.ADMIN ? 200 : 403, adminApi(token), role + " admin area");
            assertEquals(role == Role.STAFF ? 403 : 200, managerApi(token), role + " manager area");
        }
    }

    @Test
    @DisplayName("Accounts made through the real POST /api/staff endpoint (not built by hand) follow the same sequence for every role")
    void accountsCreatedThroughTheRealEndpointFollowTheSequence() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            String username = PREFIX + "made_" + role.name().toLowerCase();
            String json = """
                    {"fullName": "Made %s", "email": "%s@hotel.test", "department": "FRONT_DESK", "availability": "AVAILABLE",
                     "username": "%s", "temporaryPassword": "%s", "role": "%s"}
                    """.formatted(role, username, username, TEMP_PASSWORD, role);
            MvcResult created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("creator").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
            assertEquals(201, created.getResponse().getStatus(), role + ": " + body(created));

            User stored = userRepository.findByUsername(username).orElseThrow();
            assertEquals(role, stored.getRole());
            assertFalse(stored.isEmailVerified(), role + " starts unverified");
            assertTrue(stored.isCredentialsSetupRequired(), role + " starts with password setup required");

            String client = "10.8.5." + (++n);
            assertFalse(hasSession(login(client, username, TEMP_PASSWORD)), role.name());
            MvcResult verified = verifyEmail(client, username, emailedCode(stored));
            String setupToken = field(verified, "setupToken");
            assertNotNull(setupToken, role + ": the verify response must carry the setup token: " + body(verified));
            assertTrue(body(verified).contains("\"success\":true"), body(verified));
            assertFalse(hasSession(verified));

            assertFalse(hasSession(login(client, username, TEMP_PASSWORD)), role + " cannot sign in before setting its password");
            assertEquals(200, setup(client, setupToken, username, NEW_PASSWORD).getResponse().getStatus());
            assertEquals(400, setup(client, setupToken, username, NEW_PASSWORD).getResponse().getStatus(), "reused token");
            MvcResult in = login(client, username, NEW_PASSWORD);
            assertTrue(hasSession(in), role.name());
            assertEquals(role.name(), field(in, "role"));
        }
    }

    @Test
    @DisplayName("The verify response has the same shape for every role: a setupToken while setup is pending, none for onboarded accounts")
    void setupTokenIsReturnedOnlyWhileSetupIsPending() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            User user = newAccount(role);
            String client = "10.8.7." + (++n);
            send(post("/api/auth/email-verification/send"), client, "{\"identifier\":\"" + user.getUsername() + "\"}");
            MvcResult pending = verifyEmail(client, user.getUsername(), emailedCode(user));
            assertTrue(body(pending).contains("\"success\":true"), body(pending));
            assertNotNull(field(pending, "setupToken"), role + " pending setup: " + body(pending));

            // finish onboarding, then verify again: no setup token is offered any more
            assertEquals(200, setup(client, field(pending, "setupToken"), user.getUsername(), NEW_PASSWORD).getResponse().getStatus());
            reset(emailService);
            send(post("/api/auth/email-verification/send"), client, "{\"identifier\":\"" + user.getUsername() + "\"}");
            MvcResult onboarded = verifyEmail(client, user.getUsername(), emailedCode(user));
            assertTrue(body(onboarded).contains("\"success\":true"), body(onboarded));
            assertNull(field(onboarded, "setupToken"), role + " already onboarded: " + body(onboarded));
        }
    }

    @Test
    @DisplayName("A missing setup token is rejected")
    void missingSetupTokenIsRejected() throws Exception {
        assertEquals(400, send(put("/api/auth/credentials/setup"), "10.8.6.1",
                "{\"newUsername\":\"" + PREFIX + "x\",\"newPassword\":\"" + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}")
                .getResponse().getStatus());
    }

    // ── gates ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An unverified account cannot reach password setup, even with a token that looks valid")
    void unverifiedAccountsCannotSetAPassword() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            User user = newAccount(role);
            user.setSetupToken(SetupTokens.issue());
            userRepository.saveAndFlush(user);
            MvcResult result = setup("10.8.1." + (++n), user.getSetupToken(), user.getUsername(), NEW_PASSWORD);
            assertEquals(400, result.getResponse().getStatus(), role.name());
            assertFalse(hasSession(result));
            assertTrue(passwordEncoder.matches(TEMP_PASSWORD, userRepository.findById(user.getId()).orElseThrow().getPassword()));
        }
    }

    @Test
    @DisplayName("A verified account that has not set its password gets no session; a new code leads to a fresh setup token")
    void verifiedButIncompleteAccountsCannotSignInAndCanRecover() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            User user = newAccount(role);
            user.setEmailVerified(true);
            userRepository.saveAndFlush(user);
            String client = "10.8.2." + (++n);

            MvcResult blocked = login(client, user.getUsername(), TEMP_PASSWORD);
            assertFalse(hasSession(blocked), role.name());
            assertTrue(body(blocked).contains("\"emailVerificationRequired\":true"), body(blocked));

            MvcResult again = verifyEmail(client, user.getUsername(), emailedCode(user));
            String setupToken = field(again, "setupToken");
            assertNotNull(setupToken, role.name());
            assertEquals(200, setup(client, setupToken, user.getUsername(), NEW_PASSWORD).getResponse().getStatus());
        }
    }

    @Test
    @DisplayName("An expired setup token is rejected and cleared; an old token no longer works once a new one is issued")
    void expiredAndSupersededSetupTokensAreRejected() throws Exception {
        User user = newAccount(Role.MANAGER);
        user.setEmailVerified(true);
        user.setSetupToken("expired-token." + (System.currentTimeMillis() - 1000));
        userRepository.saveAndFlush(user);
        assertEquals(400, setup("10.8.3.1", user.getSetupToken(), user.getUsername(), NEW_PASSWORD).getResponse().getStatus());
        assertNull(userRepository.findById(user.getId()).orElseThrow().getSetupToken(), "expired token is cleared");

        // an old format token without an expiry is not accepted either
        user = userRepository.findById(user.getId()).orElseThrow();
        user.setSetupToken("legacy-token-without-expiry");
        userRepository.saveAndFlush(user);
        assertEquals(400, setup("10.8.3.1", "legacy-token-without-expiry", user.getUsername(), NEW_PASSWORD).getResponse().getStatus());

        // a new verification replaces the earlier token
        User fresh = newAccount(Role.ADMIN);
        fresh.setEmailVerified(true);
        userRepository.saveAndFlush(fresh);
        login("10.8.3.2", fresh.getUsername(), TEMP_PASSWORD);
        String first = field(verifyEmail("10.8.3.2", fresh.getUsername(), emailedCode(fresh)), "setupToken");
        reset(emailService);
        login("10.8.3.3", fresh.getUsername(), TEMP_PASSWORD);
        String second = field(verifyEmail("10.8.3.3", fresh.getUsername(), emailedCode(fresh)), "setupToken");
        assertFalse(first.equals(second));
        assertEquals(400, setup("10.8.3.4", first, fresh.getUsername(), NEW_PASSWORD).getResponse().getStatus(), "superseded token");
        assertEquals(200, setup("10.8.3.4", second, fresh.getUsername(), NEW_PASSWORD).getResponse().getStatus());
    }

    @Test
    @DisplayName("The setup token carries an expiry about 30 minutes ahead")
    void setupTokenIsShortLived() {
        String token = SetupTokens.issue();
        assertTrue(SetupTokens.isLive(token));
        long expiry = Long.parseLong(token.substring(token.lastIndexOf('.') + 1));
        long minutes = (expiry - System.currentTimeMillis()) / 60_000;
        assertTrue(minutes >= 29 && minutes <= 30, "minutes: " + minutes);
        assertFalse(SetupTokens.isLive(null));
        assertFalse(SetupTokens.isLive("no-expiry"));
        assertFalse(SetupTokens.isLive("x.notanumber"));
    }

    @Test
    @DisplayName("Accounts that already finished onboarding keep signing in normally, with no code and no forced reset")
    void alreadyOnboardedAccountsSignInNormally() throws Exception {
        int n = 0;
        for (Role role : ROLES) {
            User user = newAccount(role);
            user.setEmailVerified(true);
            user.setCredentialsSetupRequired(false);
            userRepository.saveAndFlush(user);
            MvcResult result = login("10.8.4." + (++n), user.getUsername(), TEMP_PASSWORD);
            assertEquals(200, result.getResponse().getStatus());
            assertTrue(hasSession(result), role.name());
            assertEquals(role.name(), field(result, "role"));
        }
        verify(emailService, never()).sendVerificationEmail(any(), any());
    }
}
