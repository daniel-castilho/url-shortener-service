package ca.tyny.urlshortener.core.model;

import java.util.Objects;

/**
 * Value Object representing a URL.
 * Immutable with basic format validation.
 * Comprehensive SSRF and format validation is done by {@link ca.tyny.urlshortener.core.validation.UrlValidator}.
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
