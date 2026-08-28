package ca.tyny.urlshortener.core.validation;

import ca.tyny.urlshortener.core.exception.InvalidDomainException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure helpers for custom-domain host normalization and validation.
 *
 * <p>Hosts are stored/compared lowercase, without a trailing dot or port. A valid claimed
 * host is an RFC-1123 hostname containing at least one label separator (a real domain,
 * not a bare single-label host or an IP address).
 */
public final class Hostnames {

    private static final Pattern HOSTNAME =
            Pattern.compile("^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    private Hostnames() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Lowercases, trims and strips a trailing dot from a raw host value.
     */
    public static String normalize(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim().toLowerCase(Locale.ROOT);
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Validates a normalized host. Throws {@link InvalidDomainException} when invalid.
     *
     * @param normalizedHost host already normalized via {@link #normalize(String)}
     */
    public static void validate(String normalizedHost) {
        if (normalizedHost == null || normalizedHost.isBlank()) {
            throw new InvalidDomainException("Domain host must not be empty");
        }
        if (!HOSTNAME.matcher(normalizedHost).matches() || !normalizedHost.contains(".")
                || IPV4.matcher(normalizedHost).matches()) {
            throw new InvalidDomainException("Invalid domain host: " + normalizedHost);
        }
    }

    /**
     * Normalizes the value of an HTTP {@code Host} header: lower-cases, trims, strips a
     * trailing dot and any {@code :port} suffix. Returns {@code null} for null/blank input.
     */
    public static String fromHostHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String host = raw.trim();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end > 0) {
                return normalize(host.substring(0, end + 1));
            }
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        return normalize(host);
    }
}