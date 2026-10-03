package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Shortener tuning, bound from the {@code app.shortener} prefix (see application.yaml).
 *
 * @param codeLength number of Base62 characters in an auto-generated short code
 * @param maxTtlSeconds maximum allowed {@code ttlSeconds} in a shorten request (server-side cap)
 * @param publicBaseUrl canonical public origin (scheme + host, e.g. {@code
 *     https://short.example.com}) used to build the {@code shortUrl} field in API responses; blank
 *     means "derive from the current request" (only correct when clients reach the service
 *     directly). Required in the {@code prod} profile (ProdConfigValidator) — behind a TLS edge the
 *     request-derived origin leaks the bind host/port and the wrong scheme. Links bound to a custom
 *     domain always resolve to {@code https://<domain>} regardless of this value.
 */
@ConfigurationProperties(prefix = "app.shortener")
public record ShortenerProperties(
    int codeLength, long maxTtlSeconds, @DefaultValue("") String publicBaseUrl) {}
