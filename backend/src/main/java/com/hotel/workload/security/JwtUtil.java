package com.hotel.workload.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;

@Component
public class JwtUtil implements InitializingBean {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey signingKey;

    public JwtUtil() {
    }

    public JwtUtil(String secret, long expiration) {
        this.secret = secret;
        this.expiration = expiration;
        init();
    }

    @Override
    public void afterPropertiesSet() {
        init();
    }

    public void init() {
        this.signingKey = validateAndCreateSigningKey(this.secret);
    }

    private SecretKey validateAndCreateSigningKey(String secretKeyStr) {
        if (secretKeyStr == null || secretKeyStr.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "JWT signing secret configuration is missing or empty. Please configure the JWT_SECRET environment variable."
            );
        }

        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretKeyStr.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "JWT signing secret must be a valid Base64-encoded string."
            );
        }

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT signing secret key length is insufficient. Secret must decode to at least 256 bits (32 bytes) for HMAC-SHA algorithms."
            );
        }

        try {
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to initialize JWT signing key: key is invalid for HMAC-SHA.", e
            );
        }
    }

    private SecretKey getSigningKey() {
        if (this.signingKey == null) {
            this.signingKey = validateAndCreateSigningKey(this.secret);
        }
        return this.signingKey;
    }

    /** The claim that holds the password version (see {@link #passwordVersion}) the token was issued under. */
    static final String PASSWORD_VERSION_CLAIM = "pwv";

    /**
     * A token without a password version (the form used before password changes ended sessions). The filter then falls back to comparing
     * the issue time with the last password change, at one-second precision.
     */
    public String generateToken(String username, Role role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * A token for a session that starts now. It records the account's password version, so that a later password change or reset (which
     * moves the version) ends this session: the filter compares the claim with the account's current version on every request.
     *
     * @param passwordChangedAt the account's {@code passwordChangedAt} as it is in the database right now (null if never changed)
     */
    public String generateToken(String username, Role role, LocalDateTime passwordChangedAt) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .claim(PASSWORD_VERSION_CLAIM, passwordVersion(passwordChangedAt))
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * The password version of an account: its last password change as epoch milliseconds, or 0 when the password was never changed. Pure
     * arithmetic on the stored value (read as UTC, which is only a way to turn it into a number), so no time zone or daylight-saving
     * rule can make the same stored value produce two different versions. Milliseconds, because every writer truncates to
     * milliseconds before storing and the database keeps at least that.
     */
    public static long passwordVersion(LocalDateTime passwordChangedAt) {
        return passwordChangedAt == null ? 0L : passwordChangedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * Whether a token was issued under the account's CURRENT password. With a password-version claim the two must be equal. A token without
     * one (issued before this check existed) is accepted only if it was issued no earlier than the second of the last password change, so a
     * sign-in in the same second as a change is not wrongly refused. An account whose password was never changed accepts every token
     * (apart from the signature, expiry, role and active checks done elsewhere).
     *
     * @param tokenVersion the token's password-version claim, or null if it has none
     * @param issuedAt     the token's issue time (may be null)
     */
    public static boolean matchesCurrentPassword(Long tokenVersion, Date issuedAt, LocalDateTime passwordChangedAt) {
        long current = passwordVersion(passwordChangedAt);
        if (tokenVersion != null) {
            return tokenVersion == current;
        }
        if (passwordChangedAt == null) {
            return true;
        }
        return issuedAt != null && issuedAt.getTime() >= (current / 1000) * 1000;
    }

    /** The token's password-version claim, or null when it has none. */
    public Long extractPasswordVersion(String token) {
        Object value = extractAllClaims(token).get(PASSWORD_VERSION_CLAIM);
        return value instanceof Number number ? number.longValue() : null;
    }

    public Date extractIssuedAt(String token) {
        return extractAllClaims(token).getIssuedAt();
    }
    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private io.jsonwebtoken.Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
