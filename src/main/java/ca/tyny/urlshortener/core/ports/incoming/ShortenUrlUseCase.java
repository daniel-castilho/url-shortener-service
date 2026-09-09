package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.model.ShortUrl;

import java.time.Instant;

public interface ShortenUrlUseCase {
    /**
     * Shortens a URL with an explicit expiry instant.
     * @param originalUrl the URL to shorten
     * @param customAlias optional custom alias (vanity URL)
     * @param userId optional user ID for ownership
     * @param expiresAt optional expiry instant (null = never expires)
     * @param domain optional custom domain
     */
    ShortUrl shorten(String originalUrl, String customAlias, String userId, Instant expiresAt, String domain);

    /**
     * Shortens a URL with TTL resolution in the application layer.
     * The raw {@code ttlSeconds} is resolved to an expiry instant using the server-side cap.
     */
    ShortUrl shorten(String originalUrl, String customAlias, String userId, Long ttlSeconds, String domain);

    // Convenience overloads using expiresAt
    default ShortUrl shorten(String originalUrl, String customAlias, String userId, Instant expiresAt) {
        return shorten(originalUrl, customAlias, userId, expiresAt, (String) null);
    }

    default ShortUrl shorten(String originalUrl, String customAlias, String userId) {
        return shorten(originalUrl, customAlias, userId, (Instant) null, (String) null);
    }

    default ShortUrl shorten(String originalUrl) {
        return shorten(originalUrl, null, null, (Instant) null, (String) null);
    }
}