package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shortener tuning, bound from the {@code app.shortener} prefix (see application.yaml).
 *
 * @param codeLength    number of Base62 characters in an auto-generated short code
 * @param maxTtlSeconds maximum allowed {@code ttlSeconds} in a shorten request (server-side cap)
 */
@ConfigurationProperties(prefix = "app.shortener")
public record ShortenerProperties(int codeLength, long maxTtlSeconds) {
}