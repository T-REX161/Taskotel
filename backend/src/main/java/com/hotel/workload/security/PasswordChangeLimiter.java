package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Change Password (someone with a stolen session guessing the current password): 5 tries per client and account in 15 minutes by
 * default. Only requests that reach the current-password check are counted, and a successful change clears them. Separate counters
 * from every other endpoint.
 */
@Component
public class PasswordChangeLimiter extends AttemptLimiter {

    @Autowired
    public PasswordChangeLimiter(@Value("${auth.password-change-rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.password-change-rate-limit.window-seconds:900}") long windowSeconds,
            @Value("${auth.password-change-rate-limit.max-attempts-per-client-and-account:5}") int maxPerClientAndAccount,
            @Value("${auth.password-change-rate-limit.max-attempts-per-client:20}") int maxPerClient,
            @Value("${auth.password-change-rate-limit.max-attempts-per-account:30}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    PasswordChangeLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Password change", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
