package ca.tyny.urlshortener.core.model;

import java.time.Instant;
import java.time.LocalDateTime;

public record ShortUrl(
        String id,
        String originalUrl,
        LocalDateTime createdAt,
        String userId,
        boolean isCustomAlias,
        long clickCount,
        Instant expiresAt) {

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt) {
        this(id, originalUrl, createdAt, null, false, 0, null);
    }

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt, String userId) {
        this(id, originalUrl, createdAt, userId, false, 0, null);
    }

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt, String userId,
            boolean isCustomAlias) {
        this(id, originalUrl, createdAt, userId, isCustomAlias, 0, null);
    }

    /**
     * Copy of this short URL with the given expiry instant.
     *
     * @param newExpiresAt instant at which the link expires (UTC); {@code null} = never expires
     */
    public ShortUrl withExpiresAt(Instant newExpiresAt) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, clickCount, newExpiresAt);
    }

    /**
     * Copy of this short URL with an updated click count.
     * The count itself is maintained atomically by the persistence adapter ($inc);
     * this accessor exists so reads can expose the current value.
     */
    public ShortUrl withClickCount(long newClickCount) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, newClickCount, expiresAt);
    }

    /**
     * A short URL is expired when it has an expiry and {@code now} has reached or passed it.
     * The expiry predicate is the application-logic source of truth; the MongoDB TTL index only
     * purges the document later (roughly 60 s cadence).
     *
     * @param now the reference instant (UTC)
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}