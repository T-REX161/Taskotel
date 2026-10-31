package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Credential setup (guessing a setup token): 10 per client in 15 minutes by default; there is no per-token key because the tokens are not known in advance. Separate counters from every other endpoint. */
@Component
public class CredentialSetupLimiter extends AttemptLimiter {

    @Autowired
    public CredentialSetupLimiter(@Value("${auth.credential-setup-rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.credential-setup-rate-limit.window-seconds:900}") long windowSeconds,
            @Value("${auth.credential-setup-rate-limit.max-attempts-per-client-and-account:10}") int maxPerClientAndAccount,
            @Value("${auth.credential-setup-rate-limit.max-attempts-per-client:20}") int maxPerClient,
            @Value("${auth.credential-setup-rate-limit.max-attempts-per-account:0}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    CredentialSetupLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Credential setup", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
