package com.hotel.workload.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public class JwtUtilTest {

    // Valid Base64-encoded secret that decodes to 50 bytes (>= 32 bytes / 256 bits)
    private static final String VALID_SECRET = "dGVzdC1qd3Qtc2VjcmV0LWtleS1tdXN0LWJlLWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmc=";

    // Alternative valid secret (different key for tampering tests)
    private static final String ALT_VALID_SECRET = "YW5vdGhlci12YWxpZC1qd3Qtc2VjcmV0LWtleS1mb3ItdGVzdHMtMzJieXRlcw==";

    // Base64 encoding of 18 bytes (insufficient for HMAC-SHA256, < 32 bytes)
    private static final String SHORT_SECRET = "c2hvcnQta2V5LTE2LWJ5dGVz";

    // Invalid Base64 string
    private static final String INVALID_BASE64_SECRET = "not-a-valid-base64-string!@#$%";

    private static final long EXPIRATION_MS = 86400000L;

    @Test
    @DisplayName("Generate token with valid configuration creates a valid 3-part JWT")
    public void testGenerateToken() {
        JwtUtil jwtUtil = new JwtUtil(VALID_SECRET, EXPIRATION_MS);

        String token = jwtUtil.generateToken("admin", Role.ADMIN);

        assertNotNull(token);
        assertEquals(3, token.split("\\.").length, "JWT must consist of header, payload, and signature separated by dots");
    }

    @Test
    @DisplayName("Validate token returns true for a newly generated token")
    public void testValidateToken_Success() {
        JwtUtil jwtUtil = new JwtUtil(VALID_SECRET, EXPIRATION_MS);

        String token = jwtUtil.generateToken("staff1", Role.STAFF);

        assertTrue(jwtUtil.validateToken(token), "Newly created token should be valid");
    }

    @Test
    @DisplayName("Extract username and role from a valid token")
    public void testExtractClaims() {
        JwtUtil jwtUtil = new JwtUtil(VALID_SECRET, EXPIRATION_MS);

        String token = jwtUtil.generateToken("manager_user", Role.MANAGER);

        assertEquals("manager_user", jwtUtil.extractUsername(token));
        assertEquals("MANAGER", jwtUtil.extractRole(token));
    }

    @Test
    @DisplayName("Validate token returns false when token was signed by a different key")
    public void testValidateToken_DifferentSigningKey_ReturnsFalse() {
        JwtUtil jwtUtil = new JwtUtil(VALID_SECRET, EXPIRATION_MS);
        JwtUtil altJwtUtil = new JwtUtil(ALT_VALID_SECRET, EXPIRATION_MS);

        String token = altJwtUtil.generateToken("admin", Role.ADMIN);

        assertFalse(jwtUtil.validateToken(token), "Token signed with different key should fail validation");
    }

    @Test
    @DisplayName("Validate token returns false for a malformed token")
    public void testValidateToken_MalformedToken_ReturnsFalse() {
        JwtUtil jwtUtil = new JwtUtil(VALID_SECRET, EXPIRATION_MS);

        assertFalse(jwtUtil.validateToken("not.a.valid.jwt.token"));
        assertFalse(jwtUtil.validateToken(""));
        assertFalse(jwtUtil.validateToken(null));
    }

    @Test
    @DisplayName("Validate token returns false when token has expired")
    public void testValidateToken_ExpiredToken_ReturnsFalse() {
        // Expiration in the past (-1000 ms)
        JwtUtil expiredJwtUtil = new JwtUtil(VALID_SECRET, -1000L);

        String token = expiredJwtUtil.generateToken("admin", Role.ADMIN);

        assertFalse(expiredJwtUtil.validateToken(token), "Expired token should fail validation");
    }

    @Test
    @DisplayName("Creating JwtUtil with null secret throws IllegalArgumentException")
    public void testMissingSecret_Null_ThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            new JwtUtil(null, EXPIRATION_MS);
        });
        assertTrue(ex.getMessage().contains("missing or empty"));
    }

    @Test
    @DisplayName("Creating JwtUtil with empty or blank secret throws IllegalArgumentException")
    public void testMissingSecret_EmptyOrBlank_ThrowsException() {
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () -> {
            new JwtUtil("", EXPIRATION_MS);
        });
        assertTrue(ex1.getMessage().contains("missing or empty"));

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () -> {
            new JwtUtil("   ", EXPIRATION_MS);
        });
        assertTrue(ex2.getMessage().contains("missing or empty"));
    }

    @Test
    @DisplayName("Creating JwtUtil with non-Base64 secret throws IllegalArgumentException")
    public void testInvalidBase64Secret_ThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            new JwtUtil(INVALID_BASE64_SECRET, EXPIRATION_MS);
        });
        assertTrue(ex.getMessage().contains("Base64"));
        assertFalse(ex.getMessage().contains(INVALID_BASE64_SECRET), "Exception message must not leak the secret");
    }

    @Test
    @DisplayName("Creating JwtUtil with secret shorter than 256 bits throws IllegalArgumentException")
    public void testWeakKey_ThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            new JwtUtil(SHORT_SECRET, EXPIRATION_MS);
        });
        assertTrue(ex.getMessage().contains("insufficient"));
        assertFalse(ex.getMessage().contains(SHORT_SECRET), "Exception message must not leak the secret");
    }

    @Test
    @DisplayName("Calling getSigningKey lazily when fields are set via reflection also validates secret")
    public void testLazyValidation_ReflectionTestUtils() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SHORT_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", EXPIRATION_MS);

        assertThrows(IllegalArgumentException.class, () -> {
            jwtUtil.generateToken("admin", Role.ADMIN);
        });
    }

    // Spring Boot Context Startup Tests using ApplicationContextRunner

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtUtil.class);

    @Test
    @DisplayName("Context startup succeeds when jwt.secret is a valid Base64 key of >= 256 bits")
    public void testContextStartup_ValidSecret_Succeeds() {
        contextRunner
                .withPropertyValues("jwt.secret=" + VALID_SECRET, "jwt.expiration=" + EXPIRATION_MS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtUtil.class);
                });
    }

    @Test
    @DisplayName("Context startup fails when jwt.secret property is missing")
    public void testContextStartup_MissingSecret_Fails() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withPropertyValues("jwt.expiration=" + EXPIRATION_MS)
                .run(context -> {
                    assertThat(context).hasFailed();
                });
    }

    @Test
    @DisplayName("Context startup fails when jwt.secret is empty")
    public void testContextStartup_EmptySecret_Fails() {
        contextRunner
                .withPropertyValues("jwt.secret=", "jwt.expiration=" + EXPIRATION_MS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("JWT signing secret configuration is missing or empty. Please configure the JWT_SECRET environment variable.");
                });
    }

    @Test
    @DisplayName("Context startup fails when jwt.secret is invalid Base64")
    public void testContextStartup_InvalidBase64_Fails() {
        contextRunner
                .withPropertyValues("jwt.secret=" + INVALID_BASE64_SECRET, "jwt.expiration=" + EXPIRATION_MS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("JWT signing secret must be a valid Base64-encoded string.");
                });
    }

    @Test
    @DisplayName("Context startup fails when jwt.secret decoded length is less than 32 bytes")
    public void testContextStartup_WeakSecret_Fails() {
        contextRunner
                .withPropertyValues("jwt.secret=" + SHORT_SECRET, "jwt.expiration=" + EXPIRATION_MS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .doesNotContain(SHORT_SECRET);
                });
    }
}
