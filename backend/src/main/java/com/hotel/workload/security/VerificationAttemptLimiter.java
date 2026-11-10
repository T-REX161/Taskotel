package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Verification-code attempts: 5 per client and account, 20 per client, 30 per account, in 15 minutes by default. In memory and per
 * application instance (see {@link AttemptLimiter}); the cumulative failed-attempt count and lockout kept in the database are the
 * authoritative per-account limit and hold across restarts. Counted whether or not the identifier belongs to an account.
 */
@Component
public class VerificationAttemptLimiter extends AttemptLimiter {

    @Autowired
    public VerificationAttemptLimiter(@Value("${auth.verification-verify-rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.verification-verify-rate-limit.window-seconds:900}") long windowSeconds,
            @Value("${auth.verification-verify-rate-limit.max-attempts-per-client-and-account:5}") int maxPerClientAndAccount,
            @Value("${auth.verification-verify-rate-limit.max-attempts-per-client:20}") int maxPerClient,
            @Value("${auth.verification-verify-rate-limit.max-attempts-per-account:30}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    VerificationAttemptLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Verification attempt", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
