package com.hotel.workload.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one place that decides which "client" a rate limiter sees. Every endpoint that is limited by client address asks this class.
 *
 * <p><b>Default: the direct remote address only.</b> With nothing configured, {@code X-Forwarded-For}, {@code Forwarded}, {@code X-Real-IP}
 * and every similar header are ignored, because any caller can send them. A caller therefore cannot choose which client a limit applies
 * to. Behind a reverse proxy this means every user shares the proxy's address (and one client allowance) until the proxy is configured as
 * trusted, below.
 *
 * <p><b>Optional: trusted proxies.</b> {@code auth.client-address.trusted-proxies} is a comma-separated list of IP addresses and CIDR
 * ranges (for example {@code 10.0.0.5,192.168.0.0/24,2001:db8::/32}). It is empty (off) by default, and an entry that cannot be read, or a
 * /0 range that would trust everybody, stops the application from starting. When it is set:
 * <ul>
 *   <li>Only {@code X-Forwarded-For} is read, and only when the connection's own peer address is in the list. From any other peer the
 *       header is ignored completely.</li>
 *   <li>The chain (several header lines are joined in order) is read from the right, skipping hops that are themselves trusted proxies; the
 *       first hop that is not trusted is the client. If every hop is trusted, the leftmost one is used.</li>
 *   <li>Anything unclear is refused and the peer address is used instead (so a request can never pick its own identity): an empty,
 *       malformed, non-literal (host name), zone-scoped or over-long entry, or a chain of more than {@value #MAX_HOPS} hops.</li>
 * </ul>
 * <b>Deployment requirement:</b> the trusted proxy must overwrite (or remove and re-set) any inbound {@code X-Forwarded-For} sent by the
 * outside, or at least append the address it saw, and the application must be reachable only through it. Reading from the right means a
 * client's own entries on the left are never used while the proxy appends; but a trusted range that other hosts can send from lets those
 * hosts choose the identity, and a proxy that forwards a client-supplied header unchanged as the last hop defeats the protection.
 *
 * <p><b>Optional: signed client assertion (Vercel to Render).</b> {@code auth.client-address.edge-secrets} (comma-separated, 32 to 256
 * characters each, current first; off when empty) names the shared secret used by the Vercel function that fronts the public
 * authentication endpoints. That function puts the visitor's address, which Vercel itself supplies, in {@value #CLIENT_ASSERTION_HEADER}
 * as {@code <unix seconds>;<address>;<hex HMAC-SHA256>}. The signature covers the time and the address, so the address cannot be changed,
 * the header cannot be made up without the secret, and a copy stops working after {@value #MAX_SKEW_SECONDS} seconds. Only a request that
 * carries exactly one valid assertion is identified by its address; every other request, whatever headers it sends (including a direct
 * call to Render), is identified by the peer address exactly as before. No forwarded header is read for this. When an assertion is valid
 * it is used before the trusted-proxy rules above.
 *
 * <p>Spring's own forwarded-header handling ({@code server.forward-headers-strategy}) is deliberately not enabled.
 */
@Component
public class ClientAddressResolver {

    static final String UNKNOWN = "unknown";
    static final int MAX_HOPS = 20;
    private static final int MAX_LENGTH = 64;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    /** The header the Vercel function sets: {@code <unix seconds>;<address>;<hex HMAC-SHA256 of the context, time and address>}. */
    public static final String CLIENT_ASSERTION_HEADER = "X-Taskotel-Client";
    /** Domain separation: the signed text starts with this line, so the secret is never used to sign anything else the same way. */
    static final String ASSERTION_CONTEXT = "taskotel-client-v1";
    static final long MAX_SKEW_SECONDS = 120;
    private static final int MIN_SECRET_LENGTH = 32;
    private static final int MAX_SECRET_LENGTH = 256;
    private static final int MAX_ASSERTION_LENGTH = 200;

    private final List<Range> trusted;
    /** The accepted secrets (current and, during a rotation, the previous one). Empty = signed assertions are not accepted at all. */
    private final List<byte[]> edgeSecrets;
    private final Clock clock;

    @Autowired
    public ClientAddressResolver(@Value("${auth.client-address.trusted-proxies:}") String trustedProxies,
                                 @Value("${auth.client-address.edge-secrets:}") String edgeSecrets) {
        this(trustedProxies, edgeSecrets, Clock.systemUTC());
    }

    ClientAddressResolver(String trustedProxies, String edgeSecrets, Clock clock) {
        this.trusted = parseRanges(trustedProxies);
        this.edgeSecrets = parseSecrets(edgeSecrets);
        this.clock = clock;
    }

    public ClientAddressResolver(String trustedProxies) {
        this(trustedProxies, "", Clock.systemUTC());
    }

    /** No trusted proxy: the direct remote address only. */
    public ClientAddressResolver() {
        this("");
    }

    /** The client identity for rate limiting: never null, never longer than 64 characters, never taken from an untrusted header. */
    public String resolve(HttpServletRequest request) {
        if (request == null) return UNKNOWN;
        String remote = request.getRemoteAddr();
        InetAddress peer = parseLiteral(remote);
        String peerIdentity = identity(peer, remote);
        if (!edgeSecrets.isEmpty()) {
            String asserted = assertedClient(request);
            if (asserted != null) return asserted;
        }
        if (trusted.isEmpty() || peer == null || !isTrusted(peer)) return peerIdentity;

        String chain = joinedForwardedFor(request);
        if (chain == null) return peerIdentity;
        String[] hops = chain.split(",", -1);
        if (hops.length > MAX_HOPS) return peerIdentity;
        List<InetAddress> parsed = new ArrayList<>();
        for (String hop : hops) {
            InetAddress address = parseLiteral(hop);
            if (address == null) return peerIdentity; // any unreadable hop makes the whole chain untrustworthy
            parsed.add(address);
        }
        for (int i = parsed.size() - 1; i >= 0; i--) {
            if (!isTrusted(parsed.get(i))) return parsed.get(i).getHostAddress();
        }
        return parsed.get(0).getHostAddress();
    }

    /**
     * The address named by a valid signed assertion, or null (the caller then uses the peer address). Null for: no header, more than one
     * header line, a different shape, a time more than {@value #MAX_SKEW_SECONDS} seconds from now in either direction, an address that
     * is unreadable or not a public one, or a signature that no accepted secret produces.
     */
    private String assertedClient(HttpServletRequest request) {
        Enumeration<String> lines = request.getHeaders(CLIENT_ASSERTION_HEADER);
        if (lines == null) return null;
        List<String> values = Collections.list(lines);
        if (values.size() != 1 || values.get(0) == null || values.get(0).length() > MAX_ASSERTION_LENGTH) return null;
        String[] parts = values.get(0).split(";", -1);
        if (parts.length != 3) return null;
        String seconds = parts[0];
        String address = parts[1];
        byte[] presented = fromHex(parts[2]);
        if (presented == null || seconds.isEmpty() || seconds.length() > 12 || !seconds.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
        long issuedAt = Long.parseLong(seconds);
        if (Math.abs(clock.millis() / 1000 - issuedAt) > MAX_SKEW_SECONDS) return null;
        InetAddress client = parseLiteral(address);
        if (client == null || client.isAnyLocalAddress() || client.isLoopbackAddress() || client.isLinkLocalAddress() || client.isSiteLocalAddress()) return null;
        boolean valid = false;
        for (byte[] secret : edgeSecrets) {
            valid |= MessageDigest.isEqual(sign(secret, seconds, address), presented); // no early exit: no timing signal about which secret matched
        }
        return valid ? client.getHostAddress() : null;
    }

    /** The signature the Vercel function must produce: HMAC-SHA256 over the context line, the seconds and the address (one per line). */
    static byte[] sign(byte[] secret, String seconds, String address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal((ASSERTION_CONTEXT + "\n" + seconds + "\n" + address).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lower-case hex of exactly 32 bytes (64 characters), or null: nothing else is accepted as a signature. */
    private static byte[] fromHex(String hex) {
        if (hex == null || hex.length() != 64) return null;
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            char a = hex.charAt(2 * i);
            char b = hex.charAt(2 * i + 1);
            if (!isLowerHex(a) || !isLowerHex(b)) return null;
            out[i] = (byte) (Character.digit(a, 16) * 16 + Character.digit(b, 16));
        }
        return out;
    }

    private static boolean isLowerHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    /** Comma-separated secrets (current first). Each must be 32 to 256 characters; a secret is never put in a message. */
    private static List<byte[]> parseSecrets(String configured) {
        List<byte[]> secrets = new ArrayList<>();
        if (configured == null || configured.isBlank()) return secrets;
        for (String entry : configured.split(",")) {
            String secret = entry.trim();
            if (secret.isEmpty()) continue;
            if (secret.length() < MIN_SECRET_LENGTH || secret.length() > MAX_SECRET_LENGTH) {
                throw new IllegalArgumentException("auth.client-address.edge-secrets: every secret must be " + MIN_SECRET_LENGTH + " to " + MAX_SECRET_LENGTH + " characters");
            }
            secrets.add(secret.getBytes(StandardCharsets.UTF_8));
        }
        return List.copyOf(secrets);
    }

    private static String identity(InetAddress parsed, String raw) {
        if (parsed != null) return parsed.getHostAddress();
        if (raw == null || raw.isBlank()) return UNKNOWN;
        String trimmed = raw.trim();
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }

    /** All X-Forwarded-For header lines joined with commas in order, or null when there are none. */
    private static String joinedForwardedFor(HttpServletRequest request) {
        Enumeration<String> lines = request.getHeaders("X-Forwarded-For");
        if (lines == null) return null;
        List<String> values = Collections.list(lines);
        if (values.isEmpty()) return null;
        return String.join(",", values);
    }

    private boolean isTrusted(InetAddress address) {
        for (Range range : trusted) {
            if (range.contains(address)) return true;
        }
        return false;
    }

    // ── parsing (literals only: no host name is ever looked up) ─────────────────────────────────

    /** An IPv4 or IPv6 literal, or null for anything else (blank, host name, zone id, out-of-range octet). */
    static InetAddress parseLiteral(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.isEmpty() || value.length() > 45) return null;
        // The bytes are worked out here and handed to getByAddress, which never looks anything up (getByName would try a DNS lookup for
        // a string it could not read as a literal).
        byte[] bytes = value.indexOf(':') >= 0 ? ipv6Bytes(value) : ipv4Bytes(value);
        if (bytes == null) return null;
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] ipv4Bytes(String value) {
        var m = IPV4.matcher(value);
        if (!m.matches()) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int octet = Integer.parseInt(m.group(i + 1));
            if (octet > 255) return null;
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    /** Strict IPv6: up to 8 groups of 1-4 hex digits, at most one "::", an optional dotted IPv4 tail; no zone id, brackets or port. */
    private static byte[] ipv6Bytes(String value) {
        String s = value;
        byte[] tail4 = null;
        int lastColon = s.lastIndexOf(':');
        String last = s.substring(lastColon + 1);
        if (last.indexOf('.') >= 0) {
            tail4 = ipv4Bytes(last);
            if (tail4 == null) return null;
            s = s.substring(0, lastColon + 1) + "0:0";
        }
        int double1 = s.indexOf("::");
        if (double1 != s.lastIndexOf("::")) return null;
        List<Integer> head;
        List<Integer> rest = List.of();
        if (double1 >= 0) {
            head = groups(s.substring(0, double1));
            rest = groups(s.substring(double1 + 2));
            if (head == null || rest == null || head.size() + rest.size() > 7) return null;
        } else {
            head = groups(s);
            if (head == null || head.size() != 8) return null;
        }
        int[] all = new int[8];
        for (int i = 0; i < head.size(); i++) all[i] = head.get(i);
        for (int i = 0; i < rest.size(); i++) all[8 - rest.size() + i] = rest.get(i);
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[2 * i] = (byte) (all[i] >> 8);
            bytes[2 * i + 1] = (byte) all[i];
        }
        if (tail4 != null) System.arraycopy(tail4, 0, bytes, 12, 4);
        return bytes;
    }

    /** Colon-separated groups of 1-4 hex digits (an empty text is no groups); null when anything else is found. */
    private static List<Integer> groups(String text) {
        List<Integer> result = new ArrayList<>();
        if (text.isEmpty()) return result;
        for (String group : text.split(":", -1)) {
            if (group.isEmpty() || group.length() > 4) return null;
            int number = 0;
            for (char c : group.toCharArray()) {
                int digit = Character.digit(c, 16);
                if (digit < 0 || c > 'f') return null; // ASCII hex digits only
                number = number * 16 + digit;
            }
            result.add(number);
        }
        return result;
    }

    private static List<Range> parseRanges(String configured) {
        List<Range> ranges = new ArrayList<>();
        if (configured == null || configured.isBlank()) return ranges;
        for (String entry : configured.split(",")) {
            String item = entry.trim();
            if (item.isEmpty()) continue;
            int slash = item.indexOf('/');
            String addressText = slash < 0 ? item : item.substring(0, slash);
            InetAddress address = parseLiteral(addressText);
            if (address == null) throw new IllegalArgumentException("auth.client-address.trusted-proxies: '" + item + "' is not an IP address or CIDR range");
            int bits = address.getAddress().length * 8;
            int prefix = bits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(item.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("auth.client-address.trusted-proxies: '" + item + "' has an invalid prefix length");
                }
                if (prefix < 1 || prefix > bits) {
                    throw new IllegalArgumentException("auth.client-address.trusted-proxies: '" + item + "' has an unusable prefix length (a /0 range would trust everybody)");
                }
            }
            ranges.add(new Range(address.getAddress(), prefix));
        }
        return List.copyOf(ranges);
    }

    private record Range(byte[] network, int prefix) {
        boolean contains(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (other.length != network.length) return false;
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) {
                if (other[i] != network[i]) return false;
            }
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (other[whole] & mask) == (network[whole] & mask);
        }
    }
}
