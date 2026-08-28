package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record ShortUrlResponse(
        String id,
        String originalUrl,
        String shortUrl,
        LocalDateTime createdAt,
        String userId,
        boolean isCustomAlias,
        long clickCount,
        Instant expiresAt,
        String title,
        List<String> tags,
        UtmParamsResponse utm,
        Instant deletedAt) {

    public record UtmParamsResponse(
            String source,
            String medium,
            String campaign,
            String term,
            String content) {
    }
}