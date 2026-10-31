package com.hotel.workload.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Password-reset (request and confirm) and credential-setup endpoints end to end, with small limits and a 5 second window.
 * Every test uses its own client address, so the limiter state shared by this context does not leak between tests.
 * Uses the in-memory test database, a mocked email service, and removes the users it creates.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "auth.password-reset-request-rate-limit.window-seconds=5",
        "auth.password-reset-request-rate-limit.max-attempts-per-client-and-account=2",
        "auth.password-reset-request-rate-limit.max-attempts-per-client=4",
        "auth.password-reset-confirm-rate-limit.window-seconds=5",
        "auth.password-reset-confirm-rate-limit.max-attempts-per-client-and-account=3",
        "auth.password-reset-confirm-rate-limit.max-attempts-per-client=6",
        "auth.credential-setup-rate-limit.window-seconds=5",
        "auth.credential-setup-rate-limit.max-attempts-per-client-and-account=3",
        "auth.credential-setup-rate-limit.max-attempts-per-client=6"
})
public class ResetAndSetupRateLimitTest {

    private static final String PREFIX = "rsrl_";
    private static final String EMAIL = PREFIX + "person@hotel.test";
    private static final String CODE = "123456";
    private static final String SETUP_TOKEN = "setup-token-for-" + PREFIX + "user.4102444800000";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private User user(String label, boolean setupRequired) {
        User user = new User();
        user.setUsername(PREFIX + label);
        user.setEmail(PREFIX + label + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Original@123"));
        user.setRole(setupRequired ? Role.STAFF : Role.ADMIN); // only staff accounts go through credential setup
        user.setActive(true);
        user.setEmailVerified(true);
        if (setupRequired) {
            user.setCredentialsSetupRequired(true);
            user.setSetupToken(SETUP_TOKEN);
        }
        return userRepository.saveAndFlush(user);
    }

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String client, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr(client); return request; }).content(json)).andReturn();
    }

    private int resetRequest(String client, String email) throws Exception {
        return call(post("/api/auth/password-reset/request"), client, "{\"email\":\"" + email + "\"}").getResponse().getStatus();
    }

    private MvcResult confirm(String client, String email, String code, String password) throws Exception {
        return call(post("/api/auth/password-reset/confirm"), client,
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}");
    }

    private MvcResult setup(String client, String token, String username) throws Exception {
        return call(put("/api/auth/credentials/setup"), client,
                "{\"setupToken\":\"" + token + "\",\"newUsername\":\"" + username + "\",\"newPassword\":\"Brand@New123\"}");
    }

    private static void assertGeneric429(MvcResult result) throws Exception {
        assertEquals(429, result.getResponse().getStatus());
        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertNotNull(retryAfter);
        assertTrue(Long.parseLong(retryAfter) > 0);
        assertEquals("{\"message\":\"Too many requests. Please wait a few minutes and try again.\"}", result.getResponse().getContentAsString());
    }

    // ── reset request ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Reset request: the same answers for a real and an unknown email, then 429 with Retry-After for both")
    void resetRequestLimitDoesNotRevealAccounts() throws Exception {
        user("person", false);
        String known = EMAIL, unknown = PREFIX + "nobody@hotel.test";
        List<Integer> knownCodes = List.of(resetRequest("10.2.0.1", known), resetRequest("10.2.0.1", known));
        List<Integer> unknownCodes = List.of(resetRequest("10.2.0.2", unknown), resetRequest("10.2.0.2", unknown));
        assertEquals(knownCodes, unknownCodes);
        assertEquals(List.of(200, 200), knownCodes);
        MvcResult blockedKnown = call(post("/api/auth/password-reset/request"), "10.2.0.1", "{\"email\":\"" + known + "\"}");
        MvcResult blockedUnknown = call(post("/api/auth/password-reset/request"), "10.2.0.2", "{\"email\":\"" + unknown + "\"}");
        assertGeneric429(blockedKnown);
        assertGeneric429(blockedUnknown);
        assertEquals(blockedKnown.getResponse().getContentAsString(), blockedUnknown.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Reset request: changing the email does not give a client a fresh allowance, and another client is unaffected")
    void resetRequestPerClientLimit() throws Exception {
        for (int i = 0; i < 4; i++) assertEquals(200, resetRequest("10.2.0.3", PREFIX + "x" + i + "@hotel.test"));
        assertEquals(429, resetRequest("10.2.0.3", PREFIX + "x-new@hotel.test"));
        assertEquals(200, resetRequest("10.2.0.4", PREFIX + "x0@hotel.test"));
    }

    @Test
    @DisplayName("Recovery: after the window the same client can request again")
    void resetRequestRecovers() throws Exception {
        assertEquals(200, resetRequest("10.2.0.5", EMAIL));
        assertEquals(200, resetRequest("10.2.0.5", EMAIL));
        assertEquals(429, resetRequest("10.2.0.5", EMAIL));
        Thread.sleep(5300);
        assertEquals(200, resetRequest("10.2.0.5", EMAIL));
    }

    // ── reset confirm ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Reset confirm: wrong codes are refused as before until the limit, then 429 even for the right code")
    void confirmLimit() throws Exception {
        user("person", false);
        User stored = userRepository.findByEmail(EMAIL).orElseThrow();
        stored.setPasswordResetCodeHash(passwordEncoder.encode(CODE));
        stored.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        userRepository.saveAndFlush(stored);

        for (int i = 0; i < 3; i++) assertEquals(400, confirm("10.3.0.1", EMAIL, "000000", "Another@123").getResponse().getStatus(), "wrong code " + (i + 1));
        assertGeneric429(confirm("10.3.0.1", EMAIL, CODE, "Another@123"));
        assertTrue(passwordEncoder.matches("Original@123", userRepository.findByEmail(EMAIL).orElseThrow().getPassword()), "the blocked request changed nothing");
    }

    @Test
    @DisplayName("Reset confirm: a valid code still resets the password; an expired code is still refused")
    void confirmStillWorks() throws Exception {
        user("person", false);
        User stored = userRepository.findByEmail(EMAIL).orElseThrow();
        stored.setPasswordResetCodeHash(passwordEncoder.encode(CODE));
        stored.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        userRepository.saveAndFlush(stored);
        assertEquals(200, confirm("10.3.0.2", EMAIL, CODE, "Another@123").getResponse().getStatus());
        assertTrue(passwordEncoder.matches("Another@123", userRepository.findByEmail(EMAIL).orElseThrow().getPassword()));

        stored = userRepository.findByEmail(EMAIL).orElseThrow();
        stored.setPasswordResetCodeHash(passwordEncoder.encode(CODE));
        stored.setPasswordResetExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.saveAndFlush(stored);
        assertEquals(400, confirm("10.3.0.3", EMAIL, CODE, "Third@12345").getResponse().getStatus());
    }

    // ── credential setup ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Credential setup: wrong tokens are refused as before until the limit, then 429")
    void setupLimit() throws Exception {
        for (int i = 0; i < 3; i++) assertEquals(400, setup("10.4.0.1", "guess-" + i, PREFIX + "new").getResponse().getStatus());
        assertGeneric429(setup("10.4.0.1", "guess-again", PREFIX + "new"));
        assertEquals(400, setup("10.4.0.2", "guess-0", PREFIX + "new").getResponse().getStatus(), "another client is unaffected");
    }

    @Test
    @DisplayName("Credential setup: a valid token still completes the setup and signs the user in")
    void setupStillWorks() throws Exception {
        user("user", true);
        MvcResult result = setup("10.4.0.3", SETUP_TOKEN, PREFIX + "renamed");
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("\"token\""));
        assertFalse(userRepository.findByUsername(PREFIX + "renamed").orElseThrow().isCredentialsSetupRequired());
    }

    // ── separation and logging ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Each endpoint keeps its own counters: exhausting one never blocks another, nor login")
    void countersAreNotShared() throws Exception {
        String client = "10.5.0.1";
        for (int i = 0; i < 3; i++) setup(client, "guess-" + i, PREFIX + "new");
        assertEquals(429, setup(client, "guess-x", PREFIX + "new").getResponse().getStatus());
        assertEquals(200, resetRequest(client, EMAIL), "reset request unaffected by setup attempts");
        assertEquals(400, confirm(client, EMAIL, "000000", "Another@123").getResponse().getStatus(), "reset confirm unaffected");
        assertEquals(401, call(post("/api/auth/login"), client, "{\"username\":\"" + PREFIX + "nobody\",\"password\":\"Wrong@123\"}").getResponse().getStatus(), "login unaffected");
    }

    @Test
    @DisplayName("Refusals are logged without emails, tokens, codes or passwords")
    void logsContainNoSensitiveValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AttemptLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            String secretEmail = PREFIX + "secret.person@hotel.test";
            for (int i = 0; i < 3; i++) resetRequest("10.6.0.1", secretEmail);
            for (int i = 0; i < 4; i++) setup("10.6.0.2", "very-secret-token-" + i, PREFIX + "u");
            for (int i = 0; i < 4; i++) confirm("10.6.0.3", secretEmail, "654321", "Hidden@Pass123");
            assertFalse(appender.list.isEmpty(), "the refusals were logged");
            for (ILoggingEvent event : appender.list) {
                String text = event.getFormattedMessage();
                for (String secret : new String[] {"secret.person", "very-secret-token", "654321", "Hidden@Pass123", SETUP_TOKEN}) {
                    assertFalse(text.contains(secret), "log line leaks a sensitive value: " + secret);
                }
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}
