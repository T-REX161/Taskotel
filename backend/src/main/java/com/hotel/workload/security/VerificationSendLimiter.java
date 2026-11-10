package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Verification-code send requests (each can send an email): 3 per client and account, 10 per client, 6 per account, in 15 minutes by default.
 * In memory and per application instance, so a restart clears it and several instances would each keep their own counts (see
 * {@link AttemptLimiter}); the per-account cooldown and lockout kept in the database are what hold across restarts. Every request is counted
 * whether or not the identifier belongs to an account, so a refusal never reveals which do. Separate counters from every other endpoint.
 */
@Component
public class VerificationSendLimiter extends AttemptLimiter {

    @Autowired
    public VerificationSendLimiter(@Value("${auth.verification-send-rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.verification-send-rate-limit.window-seconds:900}") long windowSeconds,
            @Value("${auth.verification-send-rate-limit.max-attempts-per-client-and-account:3}") int maxPerClientAndAccount,
            @Value("${auth.verification-send-rate-limit.max-attempts-per-client:10}") int maxPerClient,
            @Value("${auth.verification-send-rate-limit.max-attempts-per-account:6}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    VerificationSendLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Verification send", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
