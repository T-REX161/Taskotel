package com.hotel.workload.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The login endpoint end to end with small limits (3 per client+account, 6 per client). Each test uses its own client address, so
 * the shared limiter state of this context does not leak between tests. Uses the in-memory test database and removes its users.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "auth.login-rate-limit.max-attempts-per-client-and-account=3",
        "auth.login-rate-limit.max-attempts-per-client=6",
        "auth.login-rate-limit.max-attempts-per-account=30"
})
public class LoginRateLimitIntegrationTest {

    private static final String PREFIX = "ratelimit_";
    private static final String PASSWORD = "Correct@123";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        create("active", true);
        create("inactive", false);
    }

    @AfterEach
    void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private void create(String label, boolean active) {
        User user = new User();
        user.setUsername(PREFIX + label);
        user.setEmail(PREFIX + label + "@hotel.test");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.ADMIN);
        user.setActive(active);
        user.setEmailVerified(true);
        userRepository.saveAndFlush(user);
    }

    private MvcResult login(String client, String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(request -> { request.setRemoteAddr(client); return request; })
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private int status(String client, String username, String password) throws Exception {
        return login(client, username, password).getResponse().getStatus();
    }

    @Test
    @DisplayName("Unknown user, wrong password and a disabled account with a wrong password all fail with the same message")
    void failuresLookTheSameFromOutside() {
        String unknown = assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(new LoginRequest(PREFIX + "nobody", "Wrong@123"))).getMessage();
        String wrong = assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(new LoginRequest(PREFIX + "active", "Wrong@123"))).getMessage();
        String disabledWrong = assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(new LoginRequest(PREFIX + "inactive", "Wrong@123"))).getMessage();
        assertEquals(unknown, wrong);
        assertEquals(unknown, disabledWrong);
        assertEquals("Invalid username/email or password", unknown);
        assertFalse(unknown.toLowerCase().contains("disabled"));
    }

    @Test
    @DisplayName("A disabled account is still refused, and only a correct password reveals that it is disabled")
    void disabledAccountStillRefused() throws Exception {
        assertEquals("User account is disabled",
                assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(new LoginRequest(PREFIX + "inactive", PASSWORD))).getMessage());
        assertEquals(401, status("10.1.0.1", PREFIX + "inactive", PASSWORD));
    }

    @Test
    @DisplayName("Successful login still returns a token")
    void successfulLogin() throws Exception {
        MvcResult result = login("10.1.0.2", PREFIX + "active", PASSWORD);
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"token\""));
    }

    @Test
    @DisplayName("Repeated wrong passwords: 401 until the limit, then 429 with Retry-After, even for the correct password")
    void limitReached() throws Exception {
        String client = "10.1.0.3";
        for (int i = 0; i < 3; i++) assertEquals(401, status(client, PREFIX + "active", "Wrong@123"), "attempt " + (i + 1));
        MvcResult blocked = login(client, PREFIX + "active", PASSWORD);
        assertEquals(429, blocked.getResponse().getStatus());
        assertNotNull(blocked.getResponse().getHeader("Retry-After"));
        assertTrue(Long.parseLong(blocked.getResponse().getHeader("Retry-After")) > 0);
        String body = blocked.getResponse().getContentAsString();
        assertTrue(body.contains("Too many"));
        assertFalse(body.contains(PASSWORD) || body.contains("token") || body.contains(PREFIX + "active"), "nothing about the account or credentials is echoed");
    }

    @Test
    @DisplayName("Trying other usernames does not reset the limit")
    void changingUsernameDoesNotBypass() throws Exception {
        String client = "10.1.0.4";
        for (int i = 0; i < 6; i++) assertEquals(401, status(client, PREFIX + "guess" + i, "Wrong@123"));
        assertEquals(429, status(client, PREFIX + "guess-new", "Wrong@123"));
        assertEquals(429, status(client, PREFIX + "active", PASSWORD));
    }

    @Test
    @DisplayName("A different client is unaffected by a blocked one")
    void otherClientStillSignsIn() throws Exception {
        for (int i = 0; i < 3; i++) status("10.1.0.5", PREFIX + "active", "Wrong@123");
        assertEquals(429, status("10.1.0.5", PREFIX + "active", PASSWORD));
        assertEquals(200, status("10.1.0.6", PREFIX + "active", PASSWORD));
    }

    @Test
    @DisplayName("A successful login resets the count for that client and account")
    void successResetsTheCount() throws Exception {
        String client = "10.1.0.7";
        for (int i = 0; i < 2; i++) assertEquals(401, status(client, PREFIX + "active", "Wrong@123"));
        assertEquals(200, status(client, PREFIX + "active", PASSWORD));
        for (int i = 0; i < 2; i++) assertEquals(401, status(client, PREFIX + "active", "Wrong@123"));
        assertEquals(200, status(client, PREFIX + "active", PASSWORD));
    }
}
