package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.InvalidExpiryException;

import java.time.Instant;

/**
 * Application-layer helper for resolving optional TTL seconds into an absolute expiry instant.
 * <p>
 * This encapsulates the domain policy: a {@code null} TTL means "never expires",
 * and values exceeding the server-side cap ({@code maxTtlSeconds}) are rejected.
 * The cap is enforced by throwing {@link InvalidExpiryException} which maps to HTTP 400.
 */
public final class ExpiryResolver {

    private ExpiryResolver() {
    }

    /**
     * Resolves the optional {@code ttlSeconds} into an absolute UTC expiry instant.
     *
     * @param ttlSeconds       optional TTL in seconds; {@code null} means no expiry
     * @param maxTtlSeconds    server-side cap for {@code ttlSeconds} (e.g. 31536000 = 1 year)
     * @return                 expiry instant, or {@code null} if {@code ttlSeconds} is {@code null}
     * @throws InvalidExpiryException if {@code ttlSeconds} exceeds {@code maxTtlSeconds}
     */
    public static Instant resolveExpiresAt(Long ttlSeconds, long maxTtlSeconds) {
        if (ttlSeconds == null) {
            return null;
        }
        if (ttlSeconds > maxTtlSeconds) {
            throw new InvalidExpiryException(
                    "ttlSeconds exceeds the maximum of " + maxTtlSeconds + " seconds");
        }
        return Instant.now().plusSeconds(ttlSeconds);
    }
}