package ca.tyny.urlshortener.infra.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the URL cache layers (prefix {@code app.cache}).
 *
 * <p>{@code l1} tunes the in-process Caffeine front cache of the cache-aside stack (L1 Caffeine →
 * bloom filter → Redis L2); {@code bloom} tunes the Redisson bloom filter that guards against cache
 * penetration. Defaults preserve the historical hardcoded values (L1: 100 items / 5s TTL; bloom:
 * 100M expected / 1% fpp).
 */
@ConfigurationProperties(prefix = "app.cache")
public record UrlCacheProperties(
    @DefaultValue("100") long l1MaxSize,
    @DefaultValue("PT5S") Duration l1Ttl,
    @DefaultValue("100000000") long bloomExpectedInsertions,
    @DefaultValue("0.01") double bloomFalsePositiveProbability) {}
