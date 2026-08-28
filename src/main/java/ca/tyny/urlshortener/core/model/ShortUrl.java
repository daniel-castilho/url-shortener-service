package ca.tyny.urlshortener.core.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record ShortUrl(
        String id,
        String originalUrl,
        LocalDateTime createdAt,
        String userId,
        boolean isCustomAlias,
        long clickCount,
        Instant expiresAt,
        String title,
        List<String> tags,
        UtmParams utm,
        Instant deletedAt) {

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt) {
        this(id, originalUrl, createdAt, null, false, 0, null, null, null, null, null);
    }

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt, String userId) {
        this(id, originalUrl, createdAt, userId, false, 0, null, null, null, null, null);
    }

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt, String userId,
            boolean isCustomAlias) {
        this(id, originalUrl, createdAt, userId, isCustomAlias, 0, null, null, null, null, null);
    }

    /**
     * Copy of this short URL with the given expiry instant.
     *
     * @param newExpiresAt instant at which the link expires (UTC); {@code null} = never expires
     */
    public ShortUrl withExpiresAt(Instant newExpiresAt) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, clickCount, newExpiresAt, title, tags, utm, deletedAt);
    }

    /**
     * Copy of this short URL with an updated click count.
     * The count itself is maintained atomically by the persistence adapter ($inc);
     * this accessor exists so reads can expose the current value.
     */
    public ShortUrl withClickCount(long newClickCount) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, newClickCount, expiresAt, title, tags, utm, deletedAt);
    }

    /**
     * Copy of this short URL with an updated title.
     */
    public ShortUrl withTitle(String newTitle) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, clickCount, expiresAt, newTitle, tags, utm, deletedAt);
    }

    /**
     * Copy of this short URL with updated tags.
     */
    public ShortUrl withTags(List<String> newTags) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, clickCount, expiresAt, title, newTags, utm, deletedAt);
    }

    /**
     * Copy of this short URL with updated UTM parameters.
     */
    public ShortUrl withUtm(UtmParams newUtm) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, clickCount, expiresAt, title, tags, newUtm, deletedAt);
    }

    /**
     * Copy of this short URL with an updated original URL.
     */
    public ShortUrl withOriginalUrl(String newOriginalUrl) {
        return new ShortUrl(id, newOriginalUrl, createdAt, userId, isCustomAlias, clickCount, expiresAt, title, tags, utm, deletedAt);
    }

    /**
     * Copy of this short URL marked as archived (soft-deleted).
     */
    public ShortUrl archived() {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, clickCount, expiresAt, title, tags, utm, Instant.now());
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

    /**
     * Returns true if this link has been archived (soft-deleted).
     */
    public boolean isArchived() {
        return deletedAt != null;
    }
}