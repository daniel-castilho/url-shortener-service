package com.example.urlshortener.core.model;

import java.time.LocalDateTime;

public record ShortUrl(
        String id,
        String originalUrl,
        LocalDateTime createdAt,
        String userId,
        boolean isCustomAlias) {
    // Constructor for backward compatibility or convenience
    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt) {
        this(id, originalUrl, createdAt, null, false);
    }

    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt, String userId) {
        this(id, originalUrl, createdAt, userId, false);
    }
}
