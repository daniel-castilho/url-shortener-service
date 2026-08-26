package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Typed configuration for URL validation and SSRF protection (prefix {@code app.url}).
 */
@ConfigurationProperties(prefix = "app.url")
public record UrlValidationProperties(
        @DefaultValue("false") boolean allowHttp,
        @DefaultValue("2000") int dnsTimeoutMs,
        @DefaultValue("true") boolean blockPrivateIps,
        @DefaultValue("300") long dnsCacheTtlSeconds
) {
}