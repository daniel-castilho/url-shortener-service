package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.RateLimitVerdict;

/**
 * Port for per-IP rate limiting.
 *
 * Implementations must provide atomic server-side accounting (e.g. a Redis
 * token bucket driven by Redis TIME) and fail open when the backing store is
 * unavailable — rate limiting must never take down the endpoint it protects.
 */
public interface RateLimiterPort {

    /**
     * Evaluates one request against the budget of the given scope for an IP.
     * Each scope has an independent bucket: exhausting one never blocks the
     * other.
     *
     * @param scope which operation's limit to apply
     * @param ip    the resolved client IP address
     * @return verdict carrying the allow/block decision and header numbers
     */
    RateLimitVerdict tryAcquire(RateLimitScope scope, String ip);
}
