package ca.tyny.urlshortener.core.model;

/**
 * Result of one rate-limit evaluation for a scope+identity pair.
 *
 * Carries the numbers needed to emit standard throttling headers
 * ({@code RateLimit-Remaining}, {@code RateLimit-Reset}, {@code Retry-After}).
 *
 * @param allowed        whether the request may proceed
 * @param remainingTokens whole tokens left in the bucket after this decision
 * @param resetSeconds   when blocked, seconds until one token refills (>= 1);
 *                       0 when allowed
 */
public record RateLimitVerdict(boolean allowed, long remainingTokens, long resetSeconds) {

    public static RateLimitVerdict allow(long remainingTokens) {
        return new RateLimitVerdict(true, remainingTokens, 0);
    }

    public static RateLimitVerdict block(long resetSeconds) {
        return new RateLimitVerdict(false, 0, Math.max(1, resetSeconds));
    }
}
