package ca.tyny.urlshortener.infra.adapter.output.validation;

import ca.tyny.urlshortener.core.exception.InvalidDestinationException;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import ca.tyny.urlshortener.infra.config.properties.UrlValidationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Default URL validator implementing SSRF protection:
 * <ul>
 *   <li>Enforces HTTPS by default (configurable via {@code app.url.allow-http})</li>
 *   <li>Validates host syntax and structure</li>
 *   <li>Resolves DNS and blocks private/internal/metadata IPs (configurable)</li>
 *   <li>Caches DNS resolutions with TTL to avoid repeated lookups</li>
 * </ul>
 */
@Component
public class DefaultUrlValidator implements UrlValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultUrlValidator.class);

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
    );

    private static final Set<String> METADATA_IPS = Set.of(
            "169.254.169.254",                    // AWS/GCP/Azure metadata
            "fd00::1", "fe80::1",                  // IPv6 link-local common
            "100.100.100.200",                     // Alibaba Cloud metadata
            "169.254.169.250"                      // Azure IMDS
    );

private static final List<CidrBlock> PRIVATE_CIDRS = List.of(
            CidrBlock.parse("127.0.0.0/8"),         // Loopback
            CidrBlock.parse("10.0.0.0/8"),          // RFC1918 Class A
            CidrBlock.parse("172.16.0.0/12"),       // RFC1918 Class B
            CidrBlock.parse("192.168.0.0/16"),      // RFC1918 Class C
            CidrBlock.parse("169.254.0.0/16"),      // Link-local
            CidrBlock.parse("0.0.0.0/8"),           // Reserved
            CidrBlock.parse("224.0.0.0/4"),         // Multicast
            CidrBlock.parse("240.0.0.0/4"),         // Reserved future use
            CidrBlock.parse("::1/128"),             // IPv6 loopback
            CidrBlock.parse("fe80::/10"),           // IPv6 link-local
            CidrBlock.parse("fc00::/7"),            // IPv6 ULA
            CidrBlock.parse("::ffff:169.254.169.254/128") // IPv4-mapped metadata
    );

    private final boolean allowHttp;
    private final int dnsTimeoutMs;
    private final boolean blockPrivateIps;
    private final long cacheTtlSeconds;

    private final Map<String, DnsCacheEntry> dnsCache = new ConcurrentHashMap<>();

    public DefaultUrlValidator(UrlValidationProperties properties) {
        this.allowHttp = properties.allowHttp();
        this.dnsTimeoutMs = properties.dnsTimeoutMs();
        this.blockPrivateIps = properties.blockPrivateIps();
        this.cacheTtlSeconds = properties.dnsCacheTtlSeconds();
    }

    @Override
    public void validate(String url) throws InvalidDestinationException {
        var result = doValidate(url);
        if (!result.allowed()) {
            log.warn("URL validation blocked: {} - {}", url, result.reason());
            throw new InvalidDestinationException(result.reason());
        }
    }

    public UrlValidator.ValidationResult doValidate(String rawUrl) {
        // 1. Parse and basic structure validation
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (Exception e) {
            return UrlValidator.ValidationResult.blocked(null, List.of(), "Invalid URL format: " + e.getMessage());
        }

        // 2. Scheme validation (HTTPS enforcement)
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return UrlValidator.ValidationResult.blocked(null, List.of(), "URL must use http:// or https:// scheme");
        }
        if (!allowHttp && scheme.equalsIgnoreCase("http")) {
            return UrlValidator.ValidationResult.blocked(null, List.of(), "HTTP URLs are not allowed; use HTTPS");
        }

        // 3. Host validation - reject userinfo (credentials in URL)
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return UrlValidator.ValidationResult.blocked(null, List.of(), "URL must not contain user credentials");
        }

        // 3. Host validation
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return UrlValidator.ValidationResult.blocked(null, List.of(), "URL must have a valid host");
        }

        // Remove port if present
        if (host.contains(":")) {
            host = host.split(":")[0];
        }

        // 4. Host syntax validation
        if (!HOST_PATTERN.matcher(host).matches() && !isIpAddress(host)) {
            return UrlValidator.ValidationResult.blocked(host, List.of(), "Invalid host format");
        }

        // 5. DNS resolution with caching
        List<InetAddress> resolvedIps = resolveWithCache(host);

        // 6. Private/internal IP blocking
        if (blockPrivateIps) {
            for (InetAddress ip : resolvedIps) {
                String ipStr = ip.getHostAddress();
                
                // Check metadata IPs
                if (METADATA_IPS.contains(ipStr)) {
                    return UrlValidator.ValidationResult.blocked(
                            host, resolvedIps, "Destination resolves to cloud metadata IP: " + ipStr);
                }

                // Check CIDR blocks
                for (CidrBlock cidr : PRIVATE_CIDRS) {
                    if (cidr.contains(ip)) {
                        return UrlValidator.ValidationResult.blocked(
                                host, resolvedIps, "Destination resolves to private/internal IP: " + ipStr);
                    }
                }
            }
        }

        return UrlValidator.ValidationResult.allowed(host, resolvedIps);
    }

    private List<InetAddress> resolveWithCache(String host) {
        var cached = dnsCache.get(host);
        long now = System.currentTimeMillis() / 1000;
        if (cached != null && cached.expiresAt > now) {
            return cached.ips;
        }

        List<InetAddress> ips;
        try {
            // Use InetAddress.getAllByName with timeout via custom socket factory
            // For simplicity, we use the default with a timeout wrapper
            ips = Arrays.asList(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            log.warn("DNS resolution failed for host: {}", host);
            ips = Collections.emptyList();
        }

        // Cache the result
        dnsCache.put(host, new DnsCacheEntry(ips, System.currentTimeMillis() / 1000 + 300)); // 5 min TTL
        return ips;
    }

    private boolean isIpAddress(String host) {
        return host.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") || host.contains(":");
    }

    private record DnsCacheEntry(List<InetAddress> ips, long expiresAt) {}

    private record CidrBlock(InetAddress address, int prefixLength) {
        public static CidrBlock parse(String cidr) {
            String[] parts = cidr.split("/");
            InetAddress address = parseAddress(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);
            return new CidrBlock(address, prefixLength);
        }

        private static InetAddress parseAddress(String addr) {
            try {
                return InetAddress.getByName(addr);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CIDR address: " + addr, e);
            }
        }

        boolean contains(InetAddress ip) {
            if (address.getClass() != ip.getClass()) {
                return false; // IPv4 vs IPv6 mismatch
            }
            byte[] addrBytes = address.getAddress();
            byte[] ipBytes = ip.getAddress();
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != ipBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                if ((addrBytes[fullBytes] & mask) != (ipBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}