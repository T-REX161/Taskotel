package com.hotel.workload.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttemptLimiterCapacityTest {

    /** A limiter with a deliberately tiny capacity: 30 keys = room for 10 attempts that each need three new keys. */
    static class Small extends AttemptLimiter {
        Small(Clock clock, int maxKeys) {
            super("Small", clock, true, 900, 5, 20, 30, maxKeys);
        }
    }

    private LoginAttemptLimiterTest.TestClock clock;
    private Small limiter;

    @BeforeEach
    void setUp() {
        clock = new LoginAttemptLimiterTest.TestClock();
        limiter = new Small(clock, 30);
    }

    private void fill() {
        for (int i = 0; i < 10; i++) assertTrue(limiter.begin("10.0.0." + i, "user" + i).allowed(), "attempt " + i);
        assertEquals(30, limiter.trackedKeys());
    }

    @Test
    @DisplayName("The number of tracked keys never exceeds the capacity: a flood of distinct clients and accounts is refused and stores nothing")
    void capacityIsAHardBound() {
        fill();
        for (int i = 0; i < 500; i++) {
            var decision = limiter.begin("172.16." + (i / 250) + "." + (i % 250), "flood" + i);
            assertFalse(decision.allowed(), "new key " + i);
            assertEquals(AttemptLimiter.SATURATED_RETRY_SECONDS, decision.retryAfterSeconds());
        }
        assertEquals(30, limiter.trackedKeys(), "nothing was added");
    }

    @Test
    @DisplayName("A refusal at capacity is all-or-nothing: an attempt that needs one new key among tracked ones adds none")
    void partialNewKeysAddNothing() {
        fill();
        // client+account is new, client and account are tracked: still needs one slot -> refused
        assertFalse(limiter.begin("10.0.0.3", "user4").allowed());
        assertEquals(30, limiter.trackedKeys());
    }

    @Test
    @DisplayName("Keys already tracked keep being counted and limited after saturation, and are not evicted")
    void trackedKeysStayLimited() {
        fill();
        // user0 from 10.0.0.0 has used 1 of 5; four more are allowed (all three keys already exist), the sixth is refused by its OWN limit
        for (int i = 0; i < 4; i++) assertTrue(limiter.begin("10.0.0.0", "user0").allowed(), "tracked attempt " + (i + 2));
        var limited = limiter.begin("10.0.0.0", "user0");
        assertFalse(limited.allowed());
        assertTrue(limited.retryAfterSeconds() > 0 && limited.retryAfterSeconds() <= 900, "the ordinary window, not the saturation delay");
        assertEquals(30, limiter.trackedKeys());
        // and an attacker flooding new keys did not push that counter out
        for (int i = 0; i < 100; i++) limiter.begin("192.0.2." + i, "other" + i);
        assertFalse(limiter.begin("10.0.0.0", "user0").allowed(), "the counter survived the flood");
    }

    @Test
    @DisplayName("Once keys have aged out of the window, their capacity is freed (also without waiting for the periodic sweep)")
    void expiredKeysFreeCapacity() {
        fill();
        assertFalse(limiter.begin("new.client", "newuser").allowed());
        clock.advanceSeconds(901);
        assertTrue(limiter.begin("new.client", "newuser").allowed(), "the old keys were cleaned up and the new one fits");
        assertEquals(3, limiter.trackedKeys(), "only the new attempt's keys remain");
    }

    @Test
    @DisplayName("When no key has expired, a full limiter stays at its bound however many new keys are refused")
    void saturatedSweepIsThrottled() {
        fill();
        clock.advanceSeconds(901); // everything is expired, but the next sweep is due only through begin()
        assertTrue(limiter.begin("a", "a1").allowed());
        // fill it again with live keys, then refuse: no expired keys exist, nothing is freed, the map stays at the bound
        for (int i = 0; i < 9; i++) limiter.begin("c" + i, "u" + i);
        assertEquals(30, limiter.trackedKeys());
        assertFalse(limiter.begin("zz", "zz").allowed());
        assertFalse(limiter.begin("zz", "zz").allowed());
        assertEquals(30, limiter.trackedKeys());
    }

    @Test
    @DisplayName("Each limiter has its own capacity: a saturated one does not affect another instance")
    void limitersAreIndependent() {
        fill();
        Small other = new Small(clock, 30);
        LoginAttemptLimiter login = new LoginAttemptLimiter(clock, true, 900, 5, 20, 30);
        assertFalse(limiter.begin("x", "y").allowed());
        assertTrue(other.begin("x", "y").allowed());
        assertTrue(login.begin("x", "y").allowed());
    }

    @Test
    @DisplayName("A successful sign-in frees its keys, which can then be used by a new client")
    void successFreesCapacity() {
        fill();
        limiter.succeeded("10.0.0.0", "user0");
        assertTrue(limiter.trackedKeys() < 30);
    }

    @Test
    @DisplayName("Concurrent attempts at capacity: never more keys than the bound, and exactly the attempts that fit are accepted")
    void concurrentAtCapacity() throws Exception {
        int threads = 16;
        int attempts = 200;
        Small contended = new Small(clock, 60); // room for 20 attempts needing three new keys each
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            results.add(pool.submit(() -> {
                start.await();
                int allowed = 0;
                for (int i = 0; i < attempts; i++) {
                    if (contended.begin("c" + thread + "." + i, "a" + thread + "." + i).allowed()) allowed++;
                }
                return allowed;
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> result : results) total += result.get();
        pool.shutdown();
        assertEquals(20, total, "exactly capacity / 3 new attempts were accepted");
        assertEquals(60, contended.trackedKeys());
    }

    @Test
    @DisplayName("Concurrent attempts on keys that are already tracked are still all counted and limited at capacity")
    void concurrentTrackedKeysStayLimited() throws Exception {
        Small contended = new Small(clock, 3); // one attempt's keys fill it
        assertTrue(contended.begin("one", "acct").allowed());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            results.add(pool.submit(() -> { start.await(); return contended.begin("one", "acct").allowed(); }));
        }
        start.countDown();
        int allowed = 0;
        for (Future<Boolean> result : results) if (result.get()) allowed++;
        pool.shutdown();
        assertEquals(4, allowed, "5 allowed per client+account in total, one already used");
        assertEquals(3, contended.trackedKeys());
    }

    @Test
    @DisplayName("Null and odd client or account values are accepted without error and count against the capacity like any other key")
    void nullAndOddInputs() {
        assertTrue(limiter.begin(null, null).allowed());
        assertTrue(limiter.begin(null, null).allowed(), "the same 'unknown' client and empty account share keys");
        assertEquals(3, limiter.trackedKeys());
        String huge = "x".repeat(10_000);
        assertTrue(limiter.begin(huge.substring(0, 60), huge).allowed());
        assertEquals(6, limiter.trackedKeys());
    }

    @Test
    @DisplayName("The default capacity is used by the real limiters, and a capacity that cannot hold one attempt is refused")
    void defaultsAndValidation() {
        assertEquals(50_000, AttemptLimiter.DEFAULT_MAX_KEYS);
        assertThrows(IllegalArgumentException.class, () -> new Small(clock, 2));
    }
}
