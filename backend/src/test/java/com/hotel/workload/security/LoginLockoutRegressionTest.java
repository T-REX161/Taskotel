package com.hotel.workload.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the "Too many sign-in attempts" investigation. They pin the CURRENT behaviour of the login limiter,
 * including the behaviour that causes the production symptom (all users behind one proxy share one client key).
 */
class LoginLockoutRegressionTest {

    private static final String PROXY = "10.20.30.40";

    private final LoginAttemptLimiterTest.TestClock clock = new LoginAttemptLimiterTest.TestClock();

    private LoginAttemptLimiter newLimiter() {
        return new LoginAttemptLimiter(clock, true, 900, 5, 20, 30);
    }

    private static MockHttpServletRequest viaProxy(String realClient) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY);                       // what Render's proxy looks like to the application
        request.addHeader("X-Forwarded-For", realClient);   // ignored unless the proxy is configured as trusted
        return request;
    }

    @Test
    @DisplayName("Failed attempts are counted and the sixth for one client+account is refused")
    void failedAttemptsAreCounted() {
        LoginAttemptLimiter limiter = newLimiter();
        for (int i = 0; i < 5; i++) assertTrue(limiter.begin("c1", "zapcom").allowed());
        assertFalse(limiter.begin("c1", "zapcom").allowed());
    }

    @Test
    @DisplayName("A successful login resets the client+account counter")
    void successResetsCounter() {
        LoginAttemptLimiter limiter = newLimiter();
        for (int i = 0; i < 4; i++) limiter.begin("c1", "zapcom");
        assertTrue(limiter.begin("c1", "zapcom").allowed());
        limiter.succeeded("c1", "zapcom");
        for (int i = 0; i < 5; i++) assertTrue(limiter.begin("c1", "zapcom").allowed(), "fresh allowance " + i);
    }

    @Test
    @DisplayName("The lockout expires on its own after the window and is not extended by refused attempts")
    void lockoutExpires() {
        LoginAttemptLimiter limiter = newLimiter();
        for (int i = 0; i < 5; i++) limiter.begin("c1", "zapcom");
        assertFalse(limiter.begin("c1", "zapcom").allowed());
        clock.advanceSeconds(600);
        assertFalse(limiter.begin("c1", "zapcom").allowed()); // refused attempts must not push the end out
        clock.advanceSeconds(301);
        assertTrue(limiter.begin("c1", "zapcom").allowed());
    }

    @Test
    @DisplayName("Repeated (duplicate) requests each count once: a double submit burns two of five attempts")
    void repeatedRequestsEachCount() {
        LoginAttemptLimiter limiter = newLimiter();
        limiter.begin("c1", "zapcom");
        limiter.begin("c1", "zapcom");
        for (int i = 0; i < 3; i++) assertTrue(limiter.begin("c1", "zapcom").allowed());
        assertFalse(limiter.begin("c1", "zapcom").allowed());
    }

    @Test
    @DisplayName("Different clients and different accounts are isolated from each other")
    void usernameAndClientIsolation() {
        LoginAttemptLimiter limiter = newLimiter();
        for (int i = 0; i < 5; i++) limiter.begin("c1", "zapcom");
        assertFalse(limiter.begin("c1", "zapcom").allowed());
        assertTrue(limiter.begin("c2", "zapcom").allowed(), "other client, same account");
        assertTrue(limiter.begin("c1", "someone-else").allowed(), "same client, other account");
    }

    @Test
    @DisplayName("Lockout state is in memory only: a restart (new limiter instance) starts with a clean slate")
    void restartClearsState() {
        LoginAttemptLimiter before = newLimiter();
        for (int i = 0; i < 5; i++) before.begin("c1", "zapcom");
        assertFalse(before.begin("c1", "zapcom").allowed());
        LoginAttemptLimiter afterRestart = newLimiter();
        assertTrue(afterRestart.begin("c1", "zapcom").allowed());
        assertEquals(3, afterRestart.trackedKeys());
    }

    @Test
    @DisplayName("ROOT CAUSE (current behaviour): behind an untrusted proxy every user resolves to the proxy address, so "
            + "20 failures by anyone block everybody, including a user who never failed")
    void everyUserBehindTheProxyShareOneClientKey() {
        ClientAddressResolver resolver = new ClientAddressResolver(); // production default: AUTH_CLIENT_ADDRESS_TRUSTED_PROXIES empty
        assertEquals(PROXY, resolver.resolve(viaProxy("198.51.100.1")));
        assertEquals(PROXY, resolver.resolve(viaProxy("198.51.100.2")));

        LoginAttemptLimiter limiter = newLimiter();
        for (int i = 0; i < 20; i++) {
            String noisyClient = resolver.resolve(viaProxy("198.51.100." + i));
            assertTrue(limiter.begin(noisyClient, "user" + i).allowed());
        }
        String innocent = resolver.resolve(viaProxy("203.0.113.77"));
        assertFalse(limiter.begin(innocent, "zapcom").allowed(), "a user with zero failures is refused");
    }

    @Test
    @DisplayName("With the proxy configured as trusted, users are separated by their real address (the fix direction)")
    void trustedProxySeparatesUsers() {
        ClientAddressResolver resolver = new ClientAddressResolver(PROXY);
        String a = resolver.resolve(viaProxy("198.51.100.1"));
        String b = resolver.resolve(viaProxy("203.0.113.77"));
        assertEquals("198.51.100.1", a);
        assertEquals("203.0.113.77", b);

        LoginAttemptLimiter limiter = newLimiter();
        for (int i = 0; i < 20; i++) limiter.begin(a, "user" + i);
        assertFalse(limiter.begin(a, "x").allowed());
        assertTrue(limiter.begin(b, "zapcom").allowed());
    }
}
