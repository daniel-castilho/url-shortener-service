package com.example.urlshortener.infra.adapter.input.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ShortenRequest(
        @NotBlank(message = "URL cannot be empty") @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://") String originalUrl) {
}
