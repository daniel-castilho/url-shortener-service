package com.example.urlshortener.core.model;

import java.util.Objects;

/**
 * Value Object representing a URL with validation.
 * Immutable and validates URL format on construction.
 */
public record Url(String value) {

    private static final String URL_PATTERN = "^https?://.*";

    public Url {
        Objects.requireNonNull(value, "URL cannot be null");

        // Trim whitespace FIRST
        value = value.trim();

        if (value.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        if (!value.matches(URL_PATTERN)) {
            throw new IllegalArgumentException(
                    "Invalid URL format. Must start with http:// or https://");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
