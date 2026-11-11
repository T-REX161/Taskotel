package com.hotel.workload.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAddressResolverTest {

    private static MockHttpServletRequest request(String remote, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        for (String line : forwardedFor) request.addHeader("X-Forwarded-For", line);
        return request;
    }

    // ── nothing configured (the default) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("With no trusted proxy the direct remote address is the client, whatever forwarding headers say")
    void defaultIgnoresEveryForwardingHeader() {
        ClientAddressResolver resolver = new ClientAddressResolver();
        MockHttpServletRequest request = request("203.0.113.9", "198.51.100.1");
        request.addHeader("Forwarded", "for=198.51.100.2");
        request.addHeader("X-Real-IP", "198.51.100.3");
        request.addHeader("X-Client-IP", "198.51.100.4");
        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    @DisplayName("A blank trusted-proxy setting is the same as none")
    void blankSettingIsOff() {
        assertEquals("203.0.113.9", new ClientAddressResolver("  , ,").resolve(request("203.0.113.9", "198.51.100.1")));
    }

    @Test
    @DisplayName("Null request, null, blank and malformed remote addresses give a safe, bounded identity and never throw")
    void nullAndMalformedRemoteAddresses() {
        ClientAddressResolver resolver = new ClientAddressResolver();
        assertEquals("unknown", resolver.resolve(null));
        assertEquals("unknown", resolver.resolve(request(null)));
        assertEquals("unknown", resolver.resolve(request("")));
        assertEquals("unknown", resolver.resolve(request("   ")));
        assertEquals("not-an-ip", resolver.resolve(request("not-an-ip")));
        String huge = "x".repeat(500);
        assertEquals(64, resolver.resolve(request(huge)).length());
        assertEquals("10.0.0.1", resolver.resolve(request(" 10.0.0.1 ")), "surrounding spaces are not part of the identity");
    }

    @Test
    @DisplayName("IPv6 remote addresses are written in one canonical form")
    void ipv6IsCanonical() {
        ClientAddressResolver resolver = new ClientAddressResolver();
        assertEquals(resolver.resolve(request("2001:db8::1")), resolver.resolve(request("2001:0db8:0:0:0:0:0:1")));
    }

    // ── configuration is checked at start-up ──────────────────────────────────────────────────

    @Test
    @DisplayName("Unreadable entries, host names and a /0 range stop start-up instead of silently trusting everyone")
    void badConfigurationFailsFast() {
        for (String bad : new String[] {"proxy.example.com", "10.0.0.256", "10.0.0.0/33", "10.0.0.0/0", "::/0", "10.0.0.0/x", "10.0.0", "fe80::1%eth0", "300.1.1.1"}) {
            assertThrows(IllegalArgumentException.class, () -> new ClientAddressResolver(bad), bad);
        }
    }

    // ── a trusted proxy ─────────────────────────────────────────────────────────────────────────

    private final ClientAddressResolver proxied = new ClientAddressResolver("10.0.0.5, 192.168.0.0/24, 2001:db8::/32");

    @Test
    @DisplayName("From a trusted proxy, the forwarded client is used")
    void trustedProxyForwardsClient() {
        assertEquals("198.51.100.7", proxied.resolve(request("10.0.0.5", "198.51.100.7")));
        assertEquals("198.51.100.7", proxied.resolve(request("192.168.0.77", " 198.51.100.7 ")));
        assertEquals("203.0.113.1", proxied.resolve(request("2001:db8:1::9", "203.0.113.1")));
    }

    @Test
    @DisplayName("From a peer that is not trusted, the header is ignored (also just outside a trusted range)")
    void untrustedPeerIsIgnored() {
        assertEquals("10.0.0.6", proxied.resolve(request("10.0.0.6", "198.51.100.7")));
        assertEquals("192.168.1.1", proxied.resolve(request("192.168.1.1", "198.51.100.7")));
        assertEquals("203.0.113.50", proxied.resolve(request("203.0.113.50", "198.51.100.7")));
    }

    @Test
    @DisplayName("Several hops are read from the right: a client's own entries on the left cannot override what the proxies appended")
    void chainIsReadFromTheRight() {
        // client sent "1.1.1.1" itself, the real client 198.51.100.7 was added by the first proxy, then a second trusted proxy
        assertEquals("198.51.100.7", proxied.resolve(request("10.0.0.5", "1.1.1.1, 198.51.100.7, 192.168.0.9")));
        // several header lines count as one chain, in order
        assertEquals("198.51.100.7", proxied.resolve(request("10.0.0.5", "1.1.1.1", "198.51.100.7, 192.168.0.9")));
        // an untrusted hop in the middle is the client even if the left looks "better"
        assertEquals("198.51.100.8", proxied.resolve(request("10.0.0.5", "198.51.100.7, 198.51.100.8, 10.0.0.5")));
    }

    @Test
    @DisplayName("When every hop is a trusted proxy, the leftmost one is the identity")
    void allTrustedUsesLeftmost() {
        assertEquals("192.168.0.2", proxied.resolve(request("10.0.0.5", "192.168.0.2, 10.0.0.5")));
    }

    @Test
    @DisplayName("Malformed or ambiguous chains are refused: the proxy's own address is used, never an attacker-chosen one")
    void malformedChainsFallBackToThePeer() {
        String[] bad = {"", "   ", ",", "198.51.100.7,", ",198.51.100.7", "198.51.100.7,,192.168.0.9", "unknown", "evil.example.com", "198.51.100.7:8080",
                "198.51.100.300", "198.51.100", "fe80::1%eth0", "198.51.100.7; DROP", "[2001:db8::1]", "1.1.1.1 2.2.2.2", "١.1.1.1", "for=1.1.1.1"};
        for (String value : bad) {
            assertEquals("10.0.0.5", proxied.resolve(request("10.0.0.5", value)), "[" + value + "]");
        }
        // one bad entry spoils a chain that is otherwise fine
        assertEquals("10.0.0.5", proxied.resolve(request("10.0.0.5", "198.51.100.7, junk, 192.168.0.9")));
        // too many hops
        String many = String.join(",", java.util.Collections.nCopies(ClientAddressResolver.MAX_HOPS + 1, "198.51.100.7"));
        assertEquals("10.0.0.5", proxied.resolve(request("10.0.0.5", many)));
        // exactly at the bound is fine
        String atBound = String.join(",", java.util.Collections.nCopies(ClientAddressResolver.MAX_HOPS, "198.51.100.7"));
        assertEquals("198.51.100.7", proxied.resolve(request("10.0.0.5", atBound)));
    }

    @Test
    @DisplayName("A trusted proxy with no X-Forwarded-For (or only other forwarding headers) is just the proxy's address")
    void noHeaderMeansThePeer() {
        MockHttpServletRequest request = request("10.0.0.5");
        request.addHeader("Forwarded", "for=198.51.100.7");
        request.addHeader("X-Real-IP", "198.51.100.7");
        assertEquals("10.0.0.5", proxied.resolve(request), "only X-Forwarded-For is read, and only from a trusted peer");
    }

    @Test
    @DisplayName("A forwarded IPv6 client is accepted and written canonically; IPv4 and IPv6 ranges do not mix")
    void ipv6ClientsAndFamilies() {
        assertEquals(proxied.resolve(request("10.0.0.5", "2001:db8:ffff::1, 192.168.0.9")), proxied.resolve(request("10.0.0.5", "2001:0db8:ffff:0:0:0:0:1,192.168.0.9")));
        // a peer in an IPv4 trusted range is not matched by an IPv6 range and vice versa
        ClientAddressResolver v6only = new ClientAddressResolver("2001:db8::/32");
        assertEquals("10.0.0.5", v6only.resolve(request("10.0.0.5", "198.51.100.7")));
    }
}
