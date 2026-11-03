package com.hotel.workload.profile;

import com.hotel.workload.security.AttemptLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** The two rate limiters of the email change flow. Each is its own bean, so each has its own counters (separate from every other endpoint). */
public final class EmailChangeLimiters {

    private EmailChangeLimiters() {}

    /** Requests for a code (each sends an email): 5 per client and account, 10 per client, 10 per account, in one hour by default. */
    @Component
    public static class Request extends AttemptLimiter {
        @Autowired
        public Request(@Value("${auth.email-change-request-rate-limit.enabled:true}") boolean enabled,
                @Value("${auth.email-change-request-rate-limit.window-seconds:3600}") long windowSeconds,
                @Value("${auth.email-change-request-rate-limit.max-attempts-per-client-and-account:5}") int maxPerClientAndAccount,
                @Value("${auth.email-change-request-rate-limit.max-attempts-per-client:10}") int maxPerClient,
                @Value("${auth.email-change-request-rate-limit.max-attempts-per-account:10}") int maxPerAccount) {
            this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
        }

        Request(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
            super("Email change request", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
        }
    }

    /** Code submissions (guessing): 10 per client and account, 20 per client, 30 per account, in 15 minutes by default. */
    @Component
    public static class Confirm extends AttemptLimiter {
        @Autowired
        public Confirm(@Value("${auth.email-change-confirm-rate-limit.enabled:true}") boolean enabled,
                @Value("${auth.email-change-confirm-rate-limit.window-seconds:900}") long windowSeconds,
                @Value("${auth.email-change-confirm-rate-limit.max-attempts-per-client-and-account:10}") int maxPerClientAndAccount,
                @Value("${auth.email-change-confirm-rate-limit.max-attempts-per-client:20}") int maxPerClient,
                @Value("${auth.email-change-confirm-rate-limit.max-attempts-per-account:30}") int maxPerAccount) {
            this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
        }

        Confirm(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
            super("Email change confirmation", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
        }
    }
}
