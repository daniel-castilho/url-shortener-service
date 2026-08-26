package ca.tyny.urlshortener.core.model;

import java.time.LocalDateTime;

public record ShortUrl(
        String id,
        String originalUrl,
        LocalDateTime createdAt,
        String userId,
        boolean isCustomAlias,
        long clickCount) {

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt) {
        this(id, originalUrl, createdAt, null, false, 0);
    }

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt, String userId) {
        this(id, originalUrl, createdAt, userId, false, 0);
    }

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt, String userId,
            boolean isCustomAlias) {
        this(id, originalUrl, createdAt, userId, isCustomAlias, 0);
    }

    /**
     * Copy of this short URL with an updated click count.
     * The count itself is maintained atomically by the persistence adapter ($inc);
     * this accessor exists so reads can expose the current value.
     */
    public ShortUrl withClickCount(long newClickCount) {
        return new ShortUrl(id, originalUrl, createdAt, userId, isCustomAlias, newClickCount);
    }
}
