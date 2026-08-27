package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record ShortenRequest(
                @NotBlank(message = "URL cannot be empty") @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://") String originalUrl,

                @Pattern(regexp = "^[a-zA-Z0-9-_]*$", message = "Custom alias must contain only letters, numbers, hyphens and underscores") String customAlias,

                @Positive(message = "ttlSeconds must be a positive number of seconds") Long ttlSeconds) {

        public ShortenRequest(String originalUrl, String customAlias) {
                this(originalUrl, customAlias, null);
        }
}