package com.hotel.workload.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules for the web app's address (taskotel.frontend-base-url, from FRONTEND_BASE_URL), used by every email link. One place, so the
 * invitation service and the production startup check can never disagree.
 */
public final class FrontendBaseUrl {

    /** A plain dotted-quad IPv4 address: four decimal numbers 0-255 without leading zeros (the only numeric host form accepted at all). */
    private static final Pattern DOTTED_QUAD = Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    private FrontendBaseUrl() {}

    /**
     * The address tidied (surrounding spaces and trailing slashes removed) when it is a usable http(s) address, otherwise null. These are the
     * rules the invitation service has always applied: http or https only, something after the scheme, and no whitespace, quote or angle bracket.
     */
    public static String normalizeOrNull(String raw) {
        String base = raw == null ? "" : raw.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        boolean usable = (base.startsWith("http://") || base.startsWith("https://")) && base.length() > "https://".length()
                && base.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '<' || c == '>');
        return usable ? base : null;
    }

    /**
     * What is wrong with this address for PRODUCTION, or null when it is acceptable. Stricter than {@link #normalizeOrNull}: an https address with a
     * real host, a valid port, no credentials, query or fragment (a path is fine), and a host that is not the local machine or a private network.
     * Numeric hosts are accepted only as a plain public dotted-quad or IPv6 address; the other forms a browser would also read as an address
     * (a single decimal number, octal or hexadecimal parts, short forms) are refused as ambiguous. No name is ever looked up in DNS. The value
     * itself is never echoed.
     */
    public static String productionProblem(String raw) {
        if (raw == null || raw.isBlank()) return "it is empty";
        String base = normalizeOrNull(raw);
        if (base == null) return "it is not a usable http(s) address";
        if (!base.startsWith("https://")) return "it must use https://";
        URI uri;
        try {
            uri = new URI(base);
        } catch (URISyntaxException e) {
            return "it is not a valid web address";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "it has no host name";
        if (uri.getUserInfo() != null) return "it must not contain credentials";
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return "it must not contain a query or fragment";
        if (uri.getPort() == 0 || uri.getPort() > 65535) return "its port is not valid";
        return hostProblem(host);
    }

    private static String hostProblem(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            return addressProblem(host.substring(1, host.length() - 1), true);
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);      // "localhost." is the same host as "localhost"
        if (host.isEmpty()) return "it has no host name";
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("localhost.localdomain")) return "it points at the local machine";
        String lastLabel = host.substring(host.lastIndexOf('.') + 1);
        if (!lastLabel.isEmpty() && Character.isDigit(lastLabel.charAt(0))) {          // no real top-level domain starts with a digit: this is an address
            return DOTTED_QUAD.matcher(host).matches() ? addressProblem(host, false) : "it is an ambiguous numeric address";
        }
        return null;
    }

    /** A literal address (never a name: nothing here can trigger a DNS lookup). Null when it is a public address. */
    private static String addressProblem(String literal, boolean ipv6) {
        InetAddress address;
        try {
            if (ipv6) {
                if (!literal.contains(":")) return "it is not a valid web address";
                address = InetAddress.getByName(literal);                               // a literal containing ':' is parsed, never resolved
            } else {
                String[] parts = literal.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) bytes[i] = (byte) Integer.parseInt(parts[i]);
                address = InetAddress.getByAddress(bytes);
            }
        } catch (UnknownHostException | RuntimeException e) {
            return "it is not a valid web address";
        }
        byte[] b = address.getAddress();
        boolean ipv4 = b.length == 4;                                                    // IPv4-mapped IPv6 addresses are returned as IPv4
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || (ipv4 && (b[0] & 0xFF) == 0)) return "it points at the local machine";
        boolean unique = !ipv4 && (b[0] & 0xFE) == 0xFC;                                 // fc00::/7
        boolean shared = ipv4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;            // 100.64.0.0/10
        if (address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress() || unique || shared) {
            return "it points at a private or local network address";
        }
        return null;
    }
}
