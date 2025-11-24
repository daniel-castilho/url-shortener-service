package com.example.urlshortener.core.model;

import java.time.LocalDateTime;

public record ShortUrl(
    String id,
    String originalUrl,
    LocalDateTime createdAt
) {}
