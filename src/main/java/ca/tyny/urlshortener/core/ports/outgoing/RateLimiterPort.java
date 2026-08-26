package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Port for per‑IP rate limiting.
 * Implementations decide how to track request counts (e.g., Redis token
 * bucket).
 */
public interface RateLimiterPort {
    /**
     * Returns {@code true} if the request from the given IP address is allowed
     * under the configured rate limit, {@code false} otherwise.
     */
    boolean isAllowed(String ip);
}
