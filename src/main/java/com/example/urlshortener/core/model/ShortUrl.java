package com.example.urlshortener.core.model;

import java.time.LocalDateTime;

public record ShortUrl(
        String id,
        String originalUrl,
        LocalDateTime createdAt,
        String userId) {
    // Constructor for backward compatibility or convenience
    public ShortUrl(String id, String originalUrl, LocalDateTime createdAt) {
        this(id, originalUrl, createdAt, null);
    }
}
