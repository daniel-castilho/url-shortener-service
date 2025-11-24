package com.example.urlshortener.core.model;

import java.time.LocalDateTime;

public record ClickEvent(
        String shortCode,
        LocalDateTime timestamp,
        String userAgent,
        String ip) {
}
