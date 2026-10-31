package com.hotel.workload.security;

import java.time.Duration;
import java.util.UUID;

/**
 * The one-time token that lets a just-verified user set the first password. It is a random UUID followed by its expiry time, so
 * lifetime is enforced without a schema change; it is cleared when used and replaced on every new verification.
 */
public final class SetupTokens {

    public static final Duration LIFETIME = Duration.ofMinutes(30);

    private SetupTokens() {
    }

    public static String issue() {
        return UUID.randomUUID() + "." + (System.currentTimeMillis() + LIFETIME.toMillis());
    }

    /** False for a missing, malformed (including the old expiry-less form) or expired token. */
    public static boolean isLive(String token) {
        if (token == null) {
            return false;
        }
        int dot = token.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        try {
            return Long.parseLong(token.substring(dot + 1)) > System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
