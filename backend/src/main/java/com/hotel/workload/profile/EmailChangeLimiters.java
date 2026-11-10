package com.hotel.workload.profile;

import com.hotel.workload.security.AttemptLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;

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

    /**
     * Email changes an Admin or Manager starts for a Staff account (each sends two emails): 5 per client and target account, 10 per client,
     * 10 per target account, in one hour by default. Its own bean, so it cannot use up the account holder's own request allowance.
     */
    @Component
    public static class Initiate extends AttemptLimiter {
        @Autowired
        public Initiate(@Value("${auth.email-change-initiate-rate-limit.enabled:true}") boolean enabled,
                @Value("${auth.email-change-initiate-rate-limit.window-seconds:3600}") long windowSeconds,
                @Value("${auth.email-change-initiate-rate-limit.max-attempts-per-client-and-account:5}") int maxPerClientAndAccount,
                @Value("${auth.email-change-initiate-rate-limit.max-attempts-per-client:10}") int maxPerClient,
                @Value("${auth.email-change-initiate-rate-limit.max-attempts-per-account:10}") int maxPerAccount) {
            this(Clock.systemUTC(), enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
        }

        Initiate(Clock clock, boolean enabled, long windowSeconds, int maxPerClientAndAccount, int maxPerClient, int maxPerAccount) {
            super("Email change initiation", clock, enabled, windowSeconds, maxPerClientAndAccount, maxPerClient, maxPerAccount);
        }
    }

    /**
     * Messages sent to one PROPOSED address by the Admin/Manager-initiated email change: 3 per address per hour, counted over every
     * client and every target account (and for addresses that are in use as well as free ones). Nobody should need to send more than a
     * try and a resend or two to one mailbox in an hour, and this stops the endpoint from being used to mail-bomb an address from many
     * accounts or clients. It is keyed by a salted SHA-256 of the normalised address, never the address itself, so the address is not
     * held in the limiter or in its logs. In memory and per application instance (not distributed): a restart clears it and each
     * instance keeps its own counts. The unused "client" counter is switched off (0), so only the per-address count applies.
     */
    @Component
    public static class Recipient extends AttemptLimiter {
        /** Per application run: the hash cannot be matched against a list of addresses from outside this process. */
        private final byte[] salt = new byte[32];

        @Autowired
        public Recipient(@Value("${auth.email-change-recipient-rate-limit.enabled:true}") boolean enabled,
                @Value("${auth.email-change-recipient-rate-limit.window-seconds:3600}") long windowSeconds,
                @Value("${auth.email-change-recipient-rate-limit.max-per-address:3}") int maxPerAddress) {
            this(Clock.systemUTC(), enabled, windowSeconds, maxPerAddress);
        }

        Recipient(Clock clock, boolean enabled, long windowSeconds, int maxPerAddress) {
            super("Email change recipient", clock, enabled, windowSeconds, maxPerAddress, 0, maxPerAddress);
            new SecureRandom().nextBytes(salt);
        }

        /** The limiter key for a normalised address. */
        public String keyFor(String normalisedEmail) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(salt);
                return HexFormat.of().formatHex(digest.digest(normalisedEmail.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }

        /** Counts one message to this address; the "client" is a fixed label, so every client shares the address's allowance. */
        public Decision beginFor(String normalisedEmail) {
            return begin("recipient", keyFor(normalisedEmail));
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
