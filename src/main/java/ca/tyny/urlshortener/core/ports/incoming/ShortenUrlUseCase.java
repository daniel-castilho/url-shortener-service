package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.model.ShortUrl;

import java.time.Instant;

public interface ShortenUrlUseCase {
    ShortUrl shorten(String originalUrl, String customAlias, String userId, Instant expiresAt, String domain);

    default ShortUrl shorten(String originalUrl, String customAlias, String userId, Instant expiresAt) {
        return shorten(originalUrl, customAlias, userId, expiresAt, null);
    }

    default ShortUrl shorten(String originalUrl, String customAlias, String userId) {
        return shorten(originalUrl, customAlias, userId, null, null);
    }

    default ShortUrl shorten(String originalUrl) {
        return shorten(originalUrl, null, null, null, null);
    }
}