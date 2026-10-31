package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Password-reset confirmations (guessing the emailed code): 5 per client and email, 20 per client, 30 per email, in 15 minutes by default. Separate counters from every other endpoint. */
@Component
public class PasswordResetConfirmLimiter extends AttemptLimiter {

    @Autowired
    public PasswordResetConfirmLimiter(@Value("${auth.password-reset-confirm-rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.password-reset-confirm-rate-limit.window-seconds:900}") long windowSeconds,
            @Value("${auth.password-reset-confirm-rate-limit.max-attempts-per-client-and-account:5}") int maxPerClientAndAccount,
            @Value("${auth.password-reset-confirm-rate-limit.max-attempts-per-client:20}") int maxPerClient,
            @Value("${auth.password-reset-confirm-rate-limit.max-attempts-per-account:30}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    PasswordResetConfirmLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Password reset confirm", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
