package com.hotel.workload.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The signed client assertion that the Vercel function sends (ClientAddressResolver, "signed client assertion"). */
class ClientAssertionResolverTest {

    private static final String PEER = "10.20.30.40"; // what the Render proxy looks like to the application
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OLD_SECRET = "previous-secret-value-also-32-chars-ok";
    private static final String H = ClientAddressResolver.CLIENT_ASSERTION_HEADER;
    private static final long NOW_SECONDS = 1_700_000_000L;

    private final LoginAttemptLimiterTest.TestClock clock = new LoginAttemptLimiterTest.TestClock() {
        @Override public long millis() { return NOW_SECONDS * 1000 + extraMillis; }
        @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis()); }
    };
    private long extraMillis = 0;

    private ClientAddressResolver resolver(String secrets) {
        return new ClientAddressResolver("", secrets, clock);
    }

    private static String sig(String secret, String seconds, String address) {
        return HexFormat.of().formatHex(ClientAddressResolver.sign(secret.getBytes(StandardCharsets.UTF_8), seconds, address));
    }

    private static String assertion(String secret, long seconds, String address) {
        return seconds + ";" + address + ";" + sig(secret, String.valueOf(seconds), address);
    }

    private static MockHttpServletRequest request(String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        return r;
    }

    private static MockHttpServletRequest withHeader(String value) {
        MockHttpServletRequest r = request(PEER);
        if (value != null) r.addHeader(H, value);
        return r;
    }

    @Test
    @DisplayName("Cross-language test vector: the Node function and the Java verifier produce the same signature")
    void sharedVectorWithTheVercelFunction() {
        assertEquals("15ceb925a087baa66655b4f59d61461e01b6430902b34e7003f59935937539da", sig(SECRET, "1700000000", "198.51.100.7"));
        assertEquals("198.51.100.7", resolver(SECRET).resolve(withHeader("1700000000;198.51.100.7;15ceb925a087baa66655b4f59d61461e01b6430902b34e7003f59935937539da")));
    }

    @Test
    @DisplayName("Valid assertions: each visitor behind the same proxy is identified by their own address")
    void validAssertionsSeparateVisitors() {
        ClientAddressResolver r = resolver(SECRET);
        assertEquals("198.51.100.1", r.resolve(withHeader(assertion(SECRET, NOW_SECONDS, "198.51.100.1"))));
        assertEquals("198.51.100.2", r.resolve(withHeader(assertion(SECRET, NOW_SECONDS, "198.51.100.2"))));
        assertEquals("2001:db8:0:0:0:0:0:1", r.resolve(withHeader(assertion(SECRET, NOW_SECONDS, "2001:db8::1"))));
    }

    @Test
    @DisplayName("Forwarding headers are never believed, with or without a valid assertion")
    void forwardingHeadersAreIgnored() {
        ClientAddressResolver r = resolver(SECRET);
        MockHttpServletRequest forged = request(PEER);
        for (String h : new String[] {"X-Forwarded-For", "X-Real-IP", "True-Client-IP", "CF-Connecting-IP", "X-Vercel-Forwarded-For", "X-Client-IP"}) {
            forged.addHeader(h, "198.51.100.99");
        }
        forged.addHeader("Forwarded", "for=198.51.100.99");
        assertEquals(PEER, r.resolve(forged));

        MockHttpServletRequest valid = request(PEER);
        valid.addHeader("X-Forwarded-For", "198.51.100.99");
        valid.addHeader(H, assertion(SECRET, NOW_SECONDS, "198.51.100.1"));
        assertEquals("198.51.100.1", r.resolve(valid), "the signed address wins; the forwarded one is not consulted");
    }

    @Test
    @DisplayName("Missing header and direct requests use the connection's own address")
    void missingAndDirect() {
        ClientAddressResolver r = resolver(SECRET);
        assertEquals(PEER, r.resolve(withHeader(null)));
        assertEquals("203.0.113.9", r.resolve(request("203.0.113.9")));
    }

    @Test
    @DisplayName("Invalid signatures are refused: wrong secret, tampered address, tampered time, replayed for another address, bad hex")
    void invalidSignatures() {
        ClientAddressResolver r = resolver(SECRET);
        String good = assertion(SECRET, NOW_SECONDS, "198.51.100.1");
        String sig = sig(SECRET, String.valueOf(NOW_SECONDS), "198.51.100.1");
        for (String bad : new String[] {
                assertion(OLD_SECRET, NOW_SECONDS, "198.51.100.1"),
                NOW_SECONDS + ";198.51.100.2;" + sig,
                (NOW_SECONDS + 1) + ";198.51.100.1;" + sig,
                NOW_SECONDS + ";198.51.100.1;" + sig.toUpperCase(),
                NOW_SECONDS + ";198.51.100.1;" + sig.substring(1),
                NOW_SECONDS + ";198.51.100.1;" + sig + "0",
                NOW_SECONDS + ";198.51.100.1;" + "g".repeat(64),
                NOW_SECONDS + ";198.51.100.1;",
                good + ";extra",
                " " + good,
                good.replace(";", ",")}) {
            assertEquals(PEER, r.resolve(withHeader(bad)), bad);
        }
        assertEquals("198.51.100.1", r.resolve(withHeader(good)));
    }

    @Test
    @DisplayName("Time window: a copy is accepted for 120 seconds either side and refused outside it (replay protection)")
    void timeWindow() {
        ClientAddressResolver r = resolver(SECRET);
        assertEquals("198.51.100.1", r.resolve(withHeader(assertion(SECRET, NOW_SECONDS - 120, "198.51.100.1"))));
        assertEquals("198.51.100.1", r.resolve(withHeader(assertion(SECRET, NOW_SECONDS + 120, "198.51.100.1"))));
        assertEquals(PEER, r.resolve(withHeader(assertion(SECRET, NOW_SECONDS - 121, "198.51.100.1"))));
        assertEquals(PEER, r.resolve(withHeader(assertion(SECRET, NOW_SECONDS + 121, "198.51.100.1"))));
        String captured = assertion(SECRET, NOW_SECONDS, "198.51.100.1");
        extraMillis = 130_000; // the same copy, two minutes later
        assertEquals(PEER, r.resolve(withHeader(captured)));
    }

    @Test
    @DisplayName("Malformed contents are refused even when signed: bad time, bad or non-public address, over-long value")
    void malformedButSigned() {
        ClientAddressResolver r = resolver(SECRET);
        for (String address : new String[] {"evil.example.com", "999.1.1.1", "198.51.100.1:443", "10.0.0.1", "127.0.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0", "::1", "fe80::1%eth0", ""}) {
            assertEquals(PEER, r.resolve(withHeader(assertion(SECRET, NOW_SECONDS, address))), "[" + address + "]");
        }
        for (String seconds : new String[] {"", "-5", "1e9", "17000000000000", "0x10", "1700000000.5", "９９９"}) {
            assertEquals(PEER, r.resolve(withHeader(seconds + ";198.51.100.1;" + sig(SECRET, seconds, "198.51.100.1"))), "[" + seconds + "]");
        }
        assertEquals(PEER, r.resolve(withHeader("x".repeat(500))));
    }

    @Test
    @DisplayName("Two assertion headers (one forged, one real) are refused as ambiguous")
    void duplicateHeaders() {
        MockHttpServletRequest r = withHeader(assertion(SECRET, NOW_SECONDS, "198.51.100.1"));
        r.addHeader(H, assertion(SECRET, NOW_SECONDS, "198.51.100.2"));
        assertEquals(PEER, resolver(SECRET).resolve(r));
    }

    @Test
    @DisplayName("Rotation: the previous secret works while both are configured and stops when it is removed")
    void rotation() {
        String signedWithOld = assertion(OLD_SECRET, NOW_SECONDS, "198.51.100.1");
        assertEquals("198.51.100.1", resolver(SECRET + " , " + OLD_SECRET).resolve(withHeader(signedWithOld)));
        assertEquals("198.51.100.1", resolver(SECRET + "," + OLD_SECRET).resolve(withHeader(assertion(SECRET, NOW_SECONDS, "198.51.100.1"))));
        assertEquals(PEER, resolver(SECRET).resolve(withHeader(signedWithOld)));
    }

    @Test
    @DisplayName("Backward compatible: with no secret configured an assertion is just an unknown header and nothing changes")
    void offWhenNotConfigured() {
        String valid = assertion(SECRET, NOW_SECONDS, "198.51.100.1");
        assertEquals(PEER, resolver("").resolve(withHeader(valid)));
        assertEquals(PEER, resolver("  , ").resolve(withHeader(valid)));
        assertEquals(PEER, new ClientAddressResolver().resolve(withHeader(valid)));
        assertEquals(PEER, new ClientAddressResolver("10.99.0.0/16").resolve(withHeader(valid)));
    }

    @Test
    @DisplayName("Weak secret configuration stops start-up, and the message does not repeat the secret")
    void weakSecretFailsFast() {
        var e = assertThrows(IllegalArgumentException.class, () -> resolver("short-secret"));
        assertFalse(e.getMessage().contains("short-secret"));
        assertThrows(IllegalArgumentException.class, () -> resolver(SECRET + ",tiny"));
        assertThrows(IllegalArgumentException.class, () -> resolver("x".repeat(257)));
    }

    @Test
    @DisplayName("The existing trusted-proxy behaviour is unchanged")
    void trustedProxiesStillWork() {
        ClientAddressResolver r = new ClientAddressResolver("10.20.30.0/24", SECRET, clock);
        MockHttpServletRequest viaTrusted = request(PEER);
        viaTrusted.addHeader("X-Forwarded-For", "198.51.100.7");
        assertEquals("198.51.100.7", r.resolve(viaTrusted));
        MockHttpServletRequest outsider = request("203.0.113.9");
        outsider.addHeader("X-Forwarded-For", "198.51.100.7");
        assertEquals("203.0.113.9", r.resolve(outsider));
    }

    @Test
    @DisplayName("The signature depends on the exact text signed: context, time and address are all covered")
    void signatureCoversEverything() {
        byte[] a = ClientAddressResolver.sign(SECRET.getBytes(StandardCharsets.UTF_8), "1", "198.51.100.1");
        assertEquals(32, a.length);
        assertArrayEquals(a, ClientAddressResolver.sign(SECRET.getBytes(StandardCharsets.UTF_8), "1", "198.51.100.1"));
        assertFalse(java.util.Arrays.equals(a, ClientAddressResolver.sign(SECRET.getBytes(StandardCharsets.UTF_8), "11", "198.51.100.1")));
        assertFalse(java.util.Arrays.equals(a, ClientAddressResolver.sign(SECRET.getBytes(StandardCharsets.UTF_8), "1", "198.51.100.11")));
    }

    @Test
    @DisplayName("Limiter at the production thresholds (5, 20, 30 per 15 minutes): visitors are isolated, a block recovers after the window")
    void limiterIsolationAndRecovery() {
        ClientAddressResolver r = resolver(SECRET);
        var limiterClock = new LoginAttemptLimiterTest.TestClock();
        var limiter = new LoginAttemptLimiter(limiterClock, true, 900, 5, 20, 30);
        String noisy = r.resolve(withHeader(assertion(SECRET, NOW_SECONDS, "198.51.100.1")));
        String other = r.resolve(withHeader(assertion(SECRET, NOW_SECONDS, "198.51.100.2")));
        for (int i = 0; i < 20; i++) assertTrue(limiter.begin(noisy, "user" + i).allowed());
        assertFalse(limiter.begin(noisy, "zapcom").allowed());
        assertTrue(limiter.begin(other, "zapcom").allowed(), "another visitor behind the same proxy is not blocked");
        limiter.succeeded(other, "zapcom");
        limiterClock.advanceSeconds(901);
        assertTrue(limiter.begin(noisy, "zapcom").allowed(), "the block ends by itself after the window");
    }
}
