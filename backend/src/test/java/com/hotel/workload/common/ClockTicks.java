package com.hotel.workload.common;

import java.time.LocalDateTime;

/**
 * Test support for tests that assert the order of records stamped with {@code LocalDateTime.now()}, such as the audit histories, which are
 * listed newest first by {@code changedAt DESC, id DESC}.
 *
 * <p>The system clock ticks in steps (on a development machine 0.5 to 3 ms, measured), so two records created within one tick get the
 * same timestamp, and the random id then decides which one is "newer". Real people make such changes seconds apart, but a test makes them
 * back to back. Calling {@link #awaitNextTick()} after each request makes the order deterministic without a guessed sleep: it returns only
 * when the clock reads strictly later than it did on entry, so every record created afterwards is stamped strictly later than every record
 * created before the call.
 */
public final class ClockTicks {

    private static final long GIVE_UP_AFTER_NANOS = 2_000_000_000L;

    private ClockTicks() {}

    /** Returns once {@code LocalDateTime.now()} is strictly later than it was on entry (a few milliseconds at most). */
    public static void awaitNextTick() {
        LocalDateTime entered = LocalDateTime.now();
        long deadline = System.nanoTime() + GIVE_UP_AFTER_NANOS;
        while (!LocalDateTime.now().isAfter(entered)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The clock did not advance within two seconds");
            }
            Thread.onSpinWait();
        }
    }
}
