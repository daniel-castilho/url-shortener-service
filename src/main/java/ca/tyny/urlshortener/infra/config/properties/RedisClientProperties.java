package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the Redisson client timeouts (prefix {@code app.redis}).
 *
 * <p>These are the values that actually bound the Redis hot-path under an outage. The Redisson
 * starter does <b>not</b> honour {@code spring.data.redis.timeout} — {@code
 * buildSingleServerConfig} only maps host/port/password/ssl/database — so this block is what
 * enforces the "Redis ≤ 500ms" budget from ADR 0005. Defaults: command timeout 500ms, connect
 * timeout 500ms, one attempt, no command retry (fail-fast is what makes fail-open fast).
 */
@ConfigurationProperties(prefix = "app.redis")
public record RedisClientProperties(
    @DefaultValue("500") int commandTimeoutMs,
    @DefaultValue("500") int connectTimeoutMs,
    @DefaultValue("1") int retryAttempts,
    @DefaultValue("100") int retryIntervalMs) {}
