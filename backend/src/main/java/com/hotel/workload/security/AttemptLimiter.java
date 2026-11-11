package com.hotel.workload.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Limits repeated attempts on one authentication endpoint. In memory and per application instance: it uses no extra dependency and
 * no database, so a restart clears it and several instances each keep their own counts.
 *
 * <p>Each endpoint has its own subclass and therefore its own counters (login, password-reset request, password-reset confirm,
 * credential setup); a limit of 0 or less switches that one key off.
 *
 * <p>Every attempt is counted against three keys inside a sliding window (default 15 minutes), and the attempt is refused
 * while any of them is at its limit:
 * <ul>
 *   <li>client + account (default 5): the normal "wrong password" limit. Keyed by client address too, so someone far away
 *       cannot lock a real user out of their own sign-in by guessing at their username;</li>
 *   <li>client (default 20): changing the username does not give a client a fresh allowance;</li>
 *   <li>account (default 30): slows guessing against one account that is spread over many clients, at the cost that such an
 *       attack can also delay the real owner for up to one window.</li>
 * </ul>
 * <p><b>Capacity.</b> A limiter tracks at most {@value #DEFAULT_MAX_KEYS} keys (a hard bound on its memory, per limiter instance; the
 * limiters do not share a map). Keys that have aged out are removed first, so ordinary traffic never reaches the bound. When the bound is
 * reached, an attempt that would need a NEW key is refused (429, retry after {@value #SATURATED_RETRY_SECONDS} seconds) and nothing is
 * stored; an attempt whose keys are all already tracked is judged exactly as before, so counters that are active are never evicted or
 * reset to make room. The price is that a flood of distinct clients or accounts can make a limiter turn away other NEW keys until the
 * window passes: a bounded, temporary refusal chosen over letting memory grow without limit or letting an attacker push a real counter out.
 * A successful sign-in removes its own attempt and clears the client + account key. Nothing is locked permanently: attempts simply
 * age out of the window, which is also the recovery. The check and the count happen in one step under a lock, so simultaneous
 * attempts cannot all slip through before the first is counted.
 */
public class AttemptLimiter {

    private static final Logger logger = LoggerFactory.getLogger(AttemptLimiter.class);
    private static final int MAX_ACCOUNT_KEY_LENGTH = 128;
    private static final long SWEEP_EVERY_MILLIS = 60_000;
    /** Throttle for the extra clean-up done when the map is full (it scans every key, so it must not run on every refused request). */
    private static final long SATURATED_SWEEP_MILLIS = 1_000;
    /** Tracked keys per limiter instance (about 50,000 x a few hundred bytes: a few tens of megabytes at the very most). */
    public static final int DEFAULT_MAX_KEYS = 50_000;
    public static final long SATURATED_RETRY_SECONDS = 60;

    /** Outcome of {@link #begin}: whether the attempt may go on, and how long to wait if it may not. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private final Object lock = new Object();
    private final Map<String, Deque<Long>> attempts = new HashMap<>();
    private final Clock clock;
    private final boolean enabled;
    private final long windowMillis;
    private final int maxPerClientAndAccount;
    private final int maxPerClient;
    private final int maxPerAccount;
    private final int maxKeys;
    private long lastSweep;

    private final String name;

    protected AttemptLimiter(String name, Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        this(name, clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount, DEFAULT_MAX_KEYS);
    }

    protected AttemptLimiter(String name, Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount, int maxKeys) {
        if (maxKeys < 3) throw new IllegalArgumentException("A limiter needs room for at least the three keys of one attempt");
        this.maxKeys = maxKeys;
        this.name = name;
        this.clock = clock;
        this.enabled = enabled;
        this.windowMillis = windowSeconds * 1000;
        this.maxPerClientAndAccount = maxPerClientAndAccount;
        this.maxPerClient = maxPerClient;
        this.maxPerAccount = maxPerAccount;
        this.lastSweep = clock.millis();
    }

    /** Counts an attempt and says whether it may proceed. Call {@link #succeeded} afterwards if the sign-in worked. */
    public Decision begin(String client, String account) {
        if (!enabled) return new Decision(true, 0);
        String[] keys = keys(client, account);
        int[] limits = {maxPerClientAndAccount, maxPerClient, maxPerAccount};
        synchronized (lock) {
            long now = clock.millis();
            sweep(now);
            long retryAfterMillis = 0;
            for (int i = 0; i < keys.length; i++) {
                Deque<Long> times = recent(keys[i], now);
                if (times != null && limits[i] > 0 && times.size() >= limits[i]) {
                    retryAfterMillis = Math.max(retryAfterMillis, times.peekFirst() + windowMillis - now);
                }
            }
            if (retryAfterMillis > 0) {
                logger.warn("{} attempts from client {} are rate limited for {} more seconds", name, client, (retryAfterMillis + 999) / 1000);
                return new Decision(false, Math.max(1, (retryAfterMillis + 999) / 1000));
            }
            // Capacity: only keys that are not tracked yet need room. Everything above (the limits of tracked keys) has already been applied.
            if (attempts.size() + untracked(keys) > maxKeys) {
                if (now - lastSweep >= SATURATED_SWEEP_MILLIS) {
                    lastSweep = now;
                    removeExpired(now);
                }
                if (attempts.size() + untracked(keys) > maxKeys) {
                    logger.warn("{} limiter is at its capacity of {} tracked keys; a new client/account is refused for now", name, maxKeys);
                    return new Decision(false, SATURATED_RETRY_SECONDS);
                }
            }
            for (String key : keys) attempts.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
            return new Decision(true, 0);
        }
    }

    /** A successful sign-in does not count against anyone: its own attempt is removed and the client + account key starts over. */
    public void succeeded(String client, String account) {
        if (!enabled) return;
        String[] keys = keys(client, account);
        synchronized (lock) {
            attempts.remove(keys[0]);
            for (int i = 1; i < keys.length; i++) {
                Deque<Long> times = attempts.get(keys[i]);
                if (times != null) {
                    times.pollLast();
                    if (times.isEmpty()) attempts.remove(keys[i]);
                }
            }
        }
    }

    private static String[] keys(String client, String account) {
        String c = client == null ? "unknown" : client;
        String a = account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
        if (a.length() > MAX_ACCOUNT_KEY_LENGTH) a = a.substring(0, MAX_ACCOUNT_KEY_LENGTH);
        return new String[] {"ca:" + c + "|" + a, "c:" + c, "a:" + a};
    }

    /** The attempts for a key that are still inside the window (older ones are dropped); null when there are none. */
    private Deque<Long> recent(String key, long now) {
        Deque<Long> times = attempts.get(key);
        if (times == null) return null;
        while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) times.pollFirst();
        if (times.isEmpty()) {
            attempts.remove(key);
            return null;
        }
        return times;
    }

    /** Keeps memory bounded: keys nobody has touched for a whole window are removed now and then. */
    private void sweep(long now) {
        if (now - lastSweep < SWEEP_EVERY_MILLIS) return;
        lastSweep = now;
        removeExpired(now);
    }

    /** How many of these keys are not in the map yet (the three keys of one attempt are always distinct). */
    private int untracked(String[] keys) {
        int missing = 0;
        for (String key : keys) {
            if (!attempts.containsKey(key)) missing++;
        }
        return missing;
    }

    private void removeExpired(long now) {
        attempts.keySet().removeIf(key -> {
            Deque<Long> times = attempts.get(key);
            return times == null || times.isEmpty() || now - times.peekLast() >= windowMillis;
        });
    }

    int trackedKeys() {
        synchronized (lock) {
            return attempts.size();
        }
    }
}
