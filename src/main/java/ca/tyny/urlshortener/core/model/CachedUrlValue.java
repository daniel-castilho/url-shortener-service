package ca.tyny.urlshortener.core.model;

import java.time.Instant;

/**
 * Value returned from / written to the URL cache.
 *
 * @param originalUrl the long destination URL
 * @param expiresAt   instant at which the link expires ({@code null} = never)
 */
public record CachedUrlValue(String originalUrl, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}