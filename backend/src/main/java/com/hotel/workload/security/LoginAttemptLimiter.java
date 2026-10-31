package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** The sign-in limiter: five wrong passwords per client and account, 20 per client, 30 per account, in 15 minutes by default. */
@Component
public class LoginAttemptLimiter extends AttemptLimiter {

    @Autowired
    public LoginAttemptLimiter(@Value("${auth.login-rate-limit.enabled:true}") boolean enabled,
                               @Value("${auth.login-rate-limit.window-seconds:900}") long windowSeconds,
                               @Value("${auth.login-rate-limit.max-attempts-per-client-and-account:5}") int maxPerClientAndAccount,
                               @Value("${auth.login-rate-limit.max-attempts-per-client:20}") int maxPerClient,
                               @Value("${auth.login-rate-limit.max-attempts-per-account:30}") int maxPerAccount) {
        this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }

    LoginAttemptLimiter(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
        super("Login", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
    }
}
