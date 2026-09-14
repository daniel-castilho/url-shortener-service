package ca.tyny.urlshortener.infra.config.properties;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for rate limiting (prefix {@code rate-limiter}).
 *
 * <p>{@code limit}/{@code window} keep the historical shorten-endpoint keys for backward
 * compatibility; redirect settings are separate so the hot path can be tuned independently; auth
 * settings protect login/refresh from brute-force and token harvesting.
 */
@ConfigurationProperties(prefix = "rate-limiter")
public record RateLimiterProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("60") long limit,
    @DefaultValue("PT1M") Duration window,
    @DefaultValue("120") long redirectLimit,
    @DefaultValue("PT1M") Duration redirectWindow,
    @DefaultValue("10") long authLimit,
    @DefaultValue("PT1M") Duration authWindow,
    @DefaultValue("X-Forwarded-For") String clientIpHeader,
    @DefaultValue({"127.0.0.0/8", "::1/128"}) List<String> trustedProxyCidrs) {

  public RateLimiterProperties {
    trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
  }

  @Override
  public List<String> trustedProxyCidrs() {
    return List.copyOf(trustedProxyCidrs);
  }
}
