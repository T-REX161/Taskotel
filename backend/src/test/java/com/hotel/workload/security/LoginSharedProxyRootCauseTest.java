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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the root cause of the production symptom through the real POST /api/auth/login endpoint, with the deployment as it is today (no
 * edge secret configured): every request reaches the application from the same proxy address, so every visitor is the same "client".
 * Thresholds are scaled down (3 per client+account, 6 per client) so the test is quick; the logic is the production logic.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "auth.login-rate-limit.max-attempts-per-client-and-account=3",
        "auth.login-rate-limit.max-attempts-per-client=6",
        "auth.login-rate-limit.max-attempts-per-account=30"
})
class LoginSharedProxyRootCauseTest {

    private static final String PREFIX = "rootcause_";
    private static final String PASSWORD = "Correct@123";
    private static final String RENDER_PROXY = "10.55.0.1"; // the one address the application sees for everybody behind Vercel and Render
    private static final String OTHER_PROXY = "10.55.0.9"; // a separate address per test: the limiter state is shared by the whole test context

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        for (String label : new String[] {"alice", "bob"}) {
            User user = new User();
            user.setUsername(PREFIX + label);
            user.setEmail(PREFIX + label + "@hotel.test");
            user.setPassword(passwordEncoder.encode(PASSWORD));
            user.setRole(Role.ADMIN);
            user.setActive(true);
            user.setEmailVerified(true);
            userRepository.saveAndFlush(user);
        }
    }

    @AfterEach
    void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    /** One login as a visitor whose real address is {@code visitor}: it only appears in X-Forwarded-For, which the application ignores. */
    private int login(String remote, String visitor, String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr(remote); r.addHeader("X-Forwarded-For", visitor); return r; })
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("ROOT CAUSE: other visitors' wrong passwords make a different visitor's CORRECT password fail with 429")
    void correctCredentialsAreBlockedByStrangersBehindTheSameProxy() throws Exception {
        // six wrong passwords from six different visitors, for six different (unknown) names: nobody exceeds anything on their own
        for (int i = 0; i < 6; i++) assertEquals(401, login(RENDER_PROXY, "198.51.100." + i, PREFIX + "nobody" + i, "Wrong@123"));
        // a seventh visitor, who has never failed, with the right password
        assertEquals(429, login(RENDER_PROXY, "203.0.113.50", PREFIX + "alice", PASSWORD));
    }

    @Test
    @DisplayName("ROOT CAUSE: three wrong guesses at one account by a stranger lock the real owner out of that account (client+account key)")
    void strangerLocksTheRealOwnerOut() throws Exception {
        for (int i = 0; i < 3; i++) assertEquals(401, login(OTHER_PROXY, "198.51.100.66", PREFIX + "alice", "Wrong@123"));
        assertEquals(429, login(OTHER_PROXY, "203.0.113.50", PREFIX + "alice", PASSWORD));
        assertEquals(200, login(OTHER_PROXY, "203.0.113.50", PREFIX + "bob", PASSWORD), "another account is still reachable until the per-client limit is hit");
    }

    @Test
    @DisplayName("A forged X-Forwarded-For does not move a caller to a new bucket (the header is not trusted)")
    void forgedForwardedForDoesNotChangeTheBucket() throws Exception {
        for (int i = 0; i < 3; i++) assertEquals(401, login("10.55.0.2", "198.51.100." + i, PREFIX + "alice", "Wrong@123"));
        assertEquals(429, login("10.55.0.2", "198.51.100.200", PREFIX + "alice", "Wrong@123"));
    }

    @Test
    @DisplayName("Successful logins are NOT counted as failures: 40 correct logins in a row from one client are all accepted")
    void successesDoNotAccumulate() throws Exception {
        for (int i = 0; i < 40; i++) assertEquals(200, login("10.55.0.3", "203.0.113.1", PREFIX + "alice", PASSWORD), "login " + (i + 1));
    }

    @Test
    @DisplayName("Correct credentials after earlier failures (below the limit) are accepted and start a fresh count")
    void correctCredentialsAfterSomeFailures() throws Exception {
        assertEquals(401, login("10.55.0.4", "203.0.113.2", PREFIX + "alice", "Wrong@123"));
        assertEquals(401, login("10.55.0.4", "203.0.113.2", PREFIX + "alice", "Wrong@123"));
        assertEquals(200, login("10.55.0.4", "203.0.113.2", PREFIX + "alice", PASSWORD));
        assertEquals(401, login("10.55.0.4", "203.0.113.2", PREFIX + "alice", "Wrong@123"));
        assertEquals(401, login("10.55.0.4", "203.0.113.2", PREFIX + "alice", "Wrong@123"));
        assertEquals(200, login("10.55.0.4", "203.0.113.2", PREFIX + "alice", PASSWORD), "the success reset the count, so two more failures are not a lockout");
    }
}
