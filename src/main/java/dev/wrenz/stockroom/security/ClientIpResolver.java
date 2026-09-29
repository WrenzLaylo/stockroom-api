package dev.wrenz.stockroom.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Behind Azure's front end (trustForwardedFor = true), the real client address is APPENDED to
 * X-Forwarded-For, sometimes with a port. Anything before it came from the client and can be forged,
 * so only the LAST entry is trusted. Without the trusted proxy, the header is ignored entirely.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request, boolean trustForwardedFor) {
        String header = trustForwardedFor ? request.getHeader("X-Forwarded-For") : null;
        if (header == null || header.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] entries = header.split(",");
        return stripPort(entries[entries.length - 1].trim());
    }

    static String stripPort(String value) {
        if (value.startsWith("[")) {                       // [2001:db8::1]:443
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        if (value.indexOf(':') == value.lastIndexOf(':') && value.indexOf(':') > 0) {  // 203.0.113.9:54321
            return value.substring(0, value.indexOf(':'));
        }
        return value;                                      // bare IPv4 or bare IPv6
    }
}
