package com.hotel.workload.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptLimiterTest {

    /** A clock the test can move forward. */
    static class TestClock extends Clock {
        private volatile long millis = 1_000_000_000L;
        void advanceSeconds(long seconds) { millis += seconds * 1000; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private TestClock clock;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        limiter = new LoginAttemptLimiter(clock, true, 900, 5, 20, 30);
    }

    @Test
    @DisplayName("The sixth attempt for one account from one client is refused, with a wait time")
    void limitPerClientAndAccount() {
        for (int i = 0; i < 5; i++) assertTrue(limiter.begin("1.1.1.1", "alice").allowed(), "attempt " + (i + 1));
        var blocked = limiter.begin("1.1.1.1", "alice");
        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterSeconds() > 0 && blocked.retryAfterSeconds() <= 900);
    }

    @Test
    @DisplayName("Changing the username does not give a client a fresh allowance")
    void changingUsernamesDoesNotHelp() {
        for (int i = 0; i < 20; i++) assertTrue(limiter.begin("2.2.2.2", "user" + i).allowed());
        assertFalse(limiter.begin("2.2.2.2", "yet-another").allowed());
        assertFalse(limiter.begin("2.2.2.2", "alice").allowed());
    }

    @Test
    @DisplayName("Another client is not affected, so a stranger cannot lock the real user out from the real user's address")
    void otherClientsAreUnaffected() {
        for (int i = 0; i < 6; i++) limiter.begin("6.6.6.6", "alice");
        assertFalse(limiter.begin("6.6.6.6", "alice").allowed());
        assertTrue(limiter.begin("7.7.7.7", "alice").allowed());
    }

    @Test
    @DisplayName("Guessing spread across many clients is still slowed per account")
    void limitPerAccountAcrossClients() {
        for (int i = 0; i < 30; i++) assertTrue(limiter.begin("10.0.0." + i, "victim").allowed());
        assertFalse(limiter.begin("10.0.1.1", "victim").allowed());
        assertTrue(limiter.begin("10.0.1.1", "someone-else").allowed());
    }

    @Test
    @DisplayName("Recovery: once the window has passed the client can try again, and nothing is locked permanently")
    void recoversAfterTheWindow() {
        for (int i = 0; i < 5; i++) limiter.begin("3.3.3.3", "bob");
        assertFalse(limiter.begin("3.3.3.3", "bob").allowed());
        clock.advanceSeconds(899);
        assertFalse(limiter.begin("3.3.3.3", "bob").allowed(), "still inside the window");
        clock.advanceSeconds(2);
        assertTrue(limiter.begin("3.3.3.3", "bob").allowed(), "attempts aged out");
    }

    @Test
    @DisplayName("Refused attempts are not counted, so hammering the endpoint does not extend the wait")
    void refusedAttemptsDoNotExtendTheBlock() {
        for (int i = 0; i < 5; i++) limiter.begin("4.4.4.4", "carol");
        for (int i = 0; i < 100; i++) assertFalse(limiter.begin("4.4.4.4", "carol").allowed());
        clock.advanceSeconds(901);
        assertTrue(limiter.begin("4.4.4.4", "carol").allowed());
    }

    @Test
    @DisplayName("A successful sign-in clears the client + account count and is not counted itself")
    void successResets() {
        for (int i = 0; i < 4; i++) limiter.begin("5.5.5.5", "dave");
        limiter.begin("5.5.5.5", "dave");
        limiter.succeeded("5.5.5.5", "dave");
        for (int i = 0; i < 5; i++) assertTrue(limiter.begin("5.5.5.5", "dave").allowed(), "fresh allowance " + (i + 1));
        assertFalse(limiter.begin("5.5.5.5", "dave").allowed());
    }

    @Test
    @DisplayName("Many successful sign-ins from one address (an office) never reach the per-client limit")
    void successfulLoginsDoNotAccumulate() {
        for (int i = 0; i < 200; i++) {
            assertTrue(limiter.begin("8.8.8.8", "user" + i).allowed());
            limiter.succeeded("8.8.8.8", "user" + i);
        }
    }

    @Test
    @DisplayName("The account is matched ignoring case and surrounding spaces")
    void accountKeyIsNormalised() {
        for (int i = 0; i < 5; i++) limiter.begin("9.9.9.9", i % 2 == 0 ? "Alice@Hotel.com" : "  alice@hotel.com ");
        assertFalse(limiter.begin("9.9.9.9", "ALICE@hotel.com").allowed());
    }

    @Test
    @DisplayName("It can be switched off and the limits are configurable")
    void disabledAndConfigurable() {
        LoginAttemptLimiter off = new LoginAttemptLimiter(clock, false, 900, 1, 1, 1);
        for (int i = 0; i < 50; i++) assertTrue(off.begin("c", "a").allowed());
        LoginAttemptLimiter strict = new LoginAttemptLimiter(clock, true, 60, 2, 20, 30);
        assertTrue(strict.begin("c", "a").allowed());
        assertTrue(strict.begin("c", "a").allowed());
        var blocked = strict.begin("c", "a");
        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterSeconds() <= 60);
    }

    @Test
    @DisplayName("Memory stays bounded: keys nobody has used for a whole window are swept")
    void oldKeysAreSwept() {
        for (int i = 0; i < 500; i++) limiter.begin("11.0.0." + (i % 250), "rotating" + i);
        assertTrue(limiter.trackedKeys() > 0);
        clock.advanceSeconds(1000);
        limiter.begin("12.0.0.1", "fresh");
        assertEquals(3, limiter.trackedKeys(), "only the new attempt's three keys remain");
    }

    @Test
    @DisplayName("Fifty simultaneous attempts: exactly the configured number get through")
    void concurrentAttemptsCannotSlipPastTheLimit() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return limiter.begin("13.13.13.13", "target").allowed();
            }));
        }
        ready.await();
        go.countDown();
        int allowed = 0;
        for (Future<Boolean> f : results) if (f.get()) allowed++;
        pool.shutdown();
        assertEquals(5, allowed);
    }
}
