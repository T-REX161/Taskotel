package com.hotel.workload.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
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

    public String generateToken(String username, Role role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
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
