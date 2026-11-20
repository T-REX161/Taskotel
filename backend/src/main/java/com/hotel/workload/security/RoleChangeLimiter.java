package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Re-entering the password to confirm an Administrator-involving role change (someone with a stolen Administrator session guessing the
 * password): 5 tries per client and account in 15 minutes by default. Only requests that reach the password check are counted, and a
 * successful change clears them. Separate counters from every other endpoint.
 */
@Component
public class RoleChangeLimiter extends AttemptLimiter {

    @Autowired
    public RoleChangeLimiter(@Value("${auth.role-change-rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.role-change-rate-limit.window-seconds:900}") long windowSeconds,
            @Value("${auth.role-change-rate-limit.max-attempts-per-client-and-account:5}") int maxPerClientAndAccount,
            @Value("${auth.role-change-rate-limit.max-attempts-per-client:20}") int maxPerClient,
            @Value("${auth.role-change-rate-limit.max-attempts-per-account:30}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    RoleChangeLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Role change", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
