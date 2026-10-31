package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Password-reset requests (each can send an email): 3 per client and email, 10 per client, 10 per email, in one hour by default. Separate counters from every other endpoint. */
@Component
public class PasswordResetRequestLimiter extends AttemptLimiter {

    @Autowired
    public PasswordResetRequestLimiter(@Value("${auth.password-reset-request-rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.password-reset-request-rate-limit.window-seconds:3600}") long windowSeconds,
            @Value("${auth.password-reset-request-rate-limit.max-attempts-per-client-and-account:3}") int maxPerClientAndAccount,
            @Value("${auth.password-reset-request-rate-limit.max-attempts-per-client:10}") int maxPerClient,
            @Value("${auth.password-reset-request-rate-limit.max-attempts-per-account:10}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    PasswordResetRequestLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Password reset request", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
