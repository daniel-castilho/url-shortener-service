package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import ca.tyny.urlshortener.core.validation.AliasPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ShortenRequest(
    @NotBlank(message = "URL cannot be empty") @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://") String originalUrl,
    @Schema(
            maxLength = AliasPolicy.MAX_LENGTH,
            description =
                "Optional custom alias (vanity URL). Letters, numbers, hyphens and underscores.")
        @Size(
            max = AliasPolicy.MAX_LENGTH,
            message = "Custom alias must be at most " + AliasPolicy.MAX_LENGTH + " characters")
        @Pattern(
            regexp = "^[a-zA-Z0-9-_]*$",
            message = "Custom alias must contain only letters, numbers, hyphens and underscores")
        String customAlias,
    @Positive(message = "ttlSeconds must be a positive number of seconds") Long ttlSeconds,
    @Pattern(
            regexp =
                "^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$",
            message = "Domain must be a valid hostname")
        String domain) {

  public ShortenRequest(String originalUrl, String customAlias, Long ttlSeconds) {
    this(originalUrl, customAlias, ttlSeconds, null);
  }

  public ShortenRequest(String originalUrl, String customAlias) {
    this(originalUrl, customAlias, null, null);
  }
}
