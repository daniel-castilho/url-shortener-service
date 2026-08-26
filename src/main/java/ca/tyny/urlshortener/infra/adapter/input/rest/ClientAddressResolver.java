package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.infra.config.properties.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the real client IP for rate limiting.
 *
 * A forwarded header (default {@code X-Forwarded-For}, left-most entry) is
 * trusted ONLY when the direct peer matches one of the configured
 * trusted-proxy CIDRs — an untrusted client cannot spoof identities by
 * sending the header itself (best practice adopted after reviewing flowtxt
 * and spotpobre; blind header trust is a bypass vector).
 */
@Component
public class ClientAddressResolver {

    private final RateLimiterProperties properties;
    private final List<IpAddressMatcher> trustedProxies;

    public ClientAddressResolver(RateLimiterProperties properties) {
        this.properties = properties;
        this.trustedProxies = properties.trustedProxyCidrs().stream()
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrustedProxy(peer)) {
            return peer;
        }
        String forwarded = request.getHeader(properties.clientIpHeader());
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return peer;
    }

    private boolean isTrustedProxy(String peer) {
        return trustedProxies.stream().anyMatch(m -> m.matches(peer));
    }
}
