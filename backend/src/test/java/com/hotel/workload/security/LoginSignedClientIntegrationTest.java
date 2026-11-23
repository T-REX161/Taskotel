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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /api/auth/login end to end with the signed client assertion switched on (the fix). Every request arrives from the same proxy
 * address, as in production. Thresholds are scaled down (3 per client+account, 6 per client, 30 per account); the logic is the production logic.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "auth.client-address.edge-secrets=integration-edge-secret-value-32-chars-min",
        "auth.login-rate-limit.max-attempts-per-client-and-account=3",
        "auth.login-rate-limit.max-attempts-per-client=6",
        "auth.login-rate-limit.max-attempts-per-account=30"
})
class LoginSignedClientIntegrationTest {

    private static final String PREFIX = "signed_";
    private static final String PASSWORD = "Correct@123";
    private static final String SECRET = "integration-edge-secret-value-32-chars-min";
    private static final String PROXY = "10.77.0.1";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        for (String label : new String[] {"alice", "bob", "carol"}) {
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

    private static String assertion(String secret, long seconds, String address) {
        String s = String.valueOf(seconds);
        return s + ";" + address + ";" + HexFormat.of().formatHex(ClientAddressResolver.sign(secret.getBytes(StandardCharsets.UTF_8), s, address));
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private MvcResult login(String remote, String assertionHeader, String forwardedFor, String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(r -> {
                            r.setRemoteAddr(remote);
                            if (assertionHeader != null) r.addHeader(ClientAddressResolver.CLIENT_ASSERTION_HEADER, assertionHeader);
                            if (forwardedFor != null) r.addHeader("X-Forwarded-For", forwardedFor);
                            return r;
                        })
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private int asVisitor(String visitor, String username, String password) throws Exception {
        return login(PROXY, assertion(SECRET, now(), visitor), null, username, password).getResponse().getStatus();
    }

    @Test
    @DisplayName("Different visitors behind the same proxy: strangers' failures no longer block a visitor with the right password")
    void visitorsAreIsolated() throws Exception {
        for (int i = 0; i < 6; i++) assertEquals(401, asVisitor("198.51.100." + i, PREFIX + "nobody" + i, "Wrong@123"));
        assertEquals(200, asVisitor("203.0.113.50", PREFIX + "alice", PASSWORD), "the same request that was refused before the fix");
        for (int i = 0; i < 3; i++) assertEquals(401, asVisitor("198.51.100.66", PREFIX + "bob", "Wrong@123"));
        assertEquals(200, asVisitor("203.0.113.51", PREFIX + "bob", PASSWORD), "a stranger's guesses at bob do not lock bob's real owner");
    }

    @Test
    @DisplayName("Repeated failures from ONE visitor are still limited: 401 up to the limit, then 429 with Retry-After, even for the right password")
    void bruteForceFromOneVisitorStillBlocked() throws Exception {
        for (int i = 0; i < 3; i++) assertEquals(401, asVisitor("198.51.100.70", PREFIX + "alice", "Wrong@123"));
        MvcResult blocked = login(PROXY, assertion(SECRET, now(), "198.51.100.70"), null, PREFIX + "alice", PASSWORD);
        assertEquals(429, blocked.getResponse().getStatus());
        assertNotNull(blocked.getResponse().getHeader("Retry-After"));
        for (int i = 0; i < 4; i++) asVisitor("198.51.100.71", PREFIX + "guess" + i, "Wrong@123");
        for (int i = 4; i < 6; i++) assertEquals(401, asVisitor("198.51.100.71", PREFIX + "guess" + i, "Wrong@123"));
        assertEquals(429, asVisitor("198.51.100.71", PREFIX + "anything", "Wrong@123"), "changing the username does not give a visitor a fresh allowance");
    }

    @Test
    @DisplayName("Spoofed forwarding headers and forged, tampered, replayed or expired assertions all fall back to the proxy's own bucket")
    void spoofingDoesNotChooseTheBucket() throws Exception {
        String good = assertion(SECRET, now(), "198.51.100.80");
        String[] forged = {
                assertion("not-the-secret-not-the-secret-not-the-secret", now(), "198.51.100.81"),
                good.replace("198.51.100.80", "198.51.100.82"),
                assertion(SECRET, now() - 600, "198.51.100.83"),
                assertion(SECRET, now() + 600, "198.51.100.84"),
        };
        // three failures with a different forged identity each: all land in the proxy's own bucket, so the 4th is refused
        for (int i = 0; i < 3; i++) assertEquals(401, login(PROXY, forged[i], "198.51.100.9" + i, PREFIX + "carol", "Wrong@123").getResponse().getStatus());
        assertEquals(429, login(PROXY, forged[3], "198.51.100.99", PREFIX + "carol", "Wrong@123").getResponse().getStatus());
        // ... and that blocked bucket does not touch a genuinely signed visitor
        assertEquals(200, asVisitor("203.0.113.60", PREFIX + "carol", PASSWORD));
    }

    @Test
    @DisplayName("Missing or malformed headers are harmless: the request is handled, in the proxy's own bucket")
    void missingAndMalformedHeaders() throws Exception {
        String[] malformed = {null, "", ";;", "garbage", "1;2;3", "x".repeat(500), "99999999999999;198.51.100.1;" + "0".repeat(64)};
        for (String header : malformed) {
            int status = login("10.77.9." + (Math.abs(String.valueOf(header).hashCode()) % 200), header, "not an ip,,,", PREFIX + "alice", PASSWORD).getResponse().getStatus();
            assertEquals(200, status, "[" + header + "]");
        }
    }

    @Test
    @DisplayName("Successful logins never create a lockout: 40 correct logins in a row from one visitor, and failures below the limit are cleared by a success")
    void successDoesNotLockOut() throws Exception {
        for (int i = 0; i < 40; i++) assertEquals(200, asVisitor("203.0.113.90", PREFIX + "alice", PASSWORD), "login " + (i + 1));
        assertEquals(401, asVisitor("203.0.113.91", PREFIX + "alice", "Wrong@123"));
        assertEquals(401, asVisitor("203.0.113.91", PREFIX + "alice", "Wrong@123"));
        assertEquals(200, asVisitor("203.0.113.91", PREFIX + "alice", PASSWORD));
        assertEquals(401, asVisitor("203.0.113.91", PREFIX + "alice", "Wrong@123"));
        assertEquals(401, asVisitor("203.0.113.91", PREFIX + "alice", "Wrong@123"));
        assertEquals(200, asVisitor("203.0.113.91", PREFIX + "alice", PASSWORD));
    }

    @Test
    @DisplayName("Concurrency: many different visitors signing in at once are all accepted (no false blocks)")
    void concurrentDifferentVisitors() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String visitor = "203.0.114." + (i + 1);
                tasks.add(() -> asVisitor(visitor, PREFIX + "alice", PASSWORD));
            }
            for (Future<Integer> f : pool.invokeAll(tasks)) assertEquals(200, f.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Concurrency: simultaneous wrong passwords from one visitor cannot slip past the limit (exactly 3 are processed)")
    void concurrentFailuresFromOneVisitorCannotSlipThrough() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) tasks.add(() -> asVisitor("198.51.100.120", PREFIX + "bob", "Wrong@123"));
            int unauthorized = 0;
            int blocked = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                int status = f.get();
                if (status == 401) unauthorized++;
                else if (status == 429) blocked++;
            }
            assertEquals(3, unauthorized);
            assertEquals(7, blocked);
        } finally {
            pool.shutdownNow();
        }
    }
}
