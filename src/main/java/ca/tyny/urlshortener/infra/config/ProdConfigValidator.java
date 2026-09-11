package ca.tyny.urlshortener.infra.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProdConfigValidator {

  private final Environment environment;
  private final boolean isProdProfile;

  public ProdConfigValidator(Environment environment) {
    this.environment = environment;
    this.isProdProfile = environment.acceptsProfiles("prod");
  }

  @EventListener(ApplicationReadyEvent.class)
  public void validate() {
    if (!isProdProfile) {
      return; // Only validate in prod profile
    }

    List<String> errors = new ArrayList<>();

    // JWT secret validation (matches JwtTokenProvider requirement)
    String jwtSecret = environment.getProperty("app.jwt.secret");
    if (jwtSecret == null || jwtSecret.isBlank()) {
      errors.add("app.jwt.secret is required in production");
    } else if (jwtSecret.length() < 32) {
      errors.add(
          "app.jwt.secret must be at least 32 characters (current: " + jwtSecret.length() + ")");
    } else if (isDefaultJwtSecret(jwtSecret)) {
      errors.add(
          "app.jwt.secret appears to be the default development value — must be overridden in production");
    }

    // MongoDB URI
    String mongoUri = environment.getProperty("spring.mongodb.uri");
    if (mongoUri == null || mongoUri.isBlank()) {
      errors.add("spring.mongodb.uri is required");
    } else if (mongoUri.contains("localhost") || mongoUri.contains("127.0.0.1")) {
      errors.add("spring.mongodb.uri should not point to localhost in production");
    }

    // Redis host
    String redisHost = environment.getProperty("spring.data.redis.host");
    if (redisHost == null || redisHost.isBlank()) {
      errors.add("spring.data.redis.host is required");
    } else if ("localhost".equals(redisHost) || "127.0.0.1".equals(redisHost)) {
      errors.add("spring.data.redis.host should not be localhost in production");
    }

    // Rate limiter trusted proxy CIDRs (must be configured for proxy deployment)
    String trustedProxies = environment.getProperty("rate-limiter.trusted-proxy-cidrs");
    if (trustedProxies == null || trustedProxies.isBlank()) {
      errors.add(
          "rate-limiter.trusted-proxy-cidrs must be configured for reverse proxy deployment");
    }

    // OTel endpoint for tracing
    String otelEndpoint = environment.getProperty("management.otlp.tracing.endpoint");
    if (otelEndpoint == null || otelEndpoint.isBlank()) {
      errors.add(
          "management.otlp.tracing.endpoint is required for distributed tracing in production");
    }

    // Analytics retention
    String retentionDays = environment.getProperty("app.analytics.retention-days");
    if (retentionDays == null || retentionDays.isBlank()) {
      errors.add("app.analytics.retention-days is required for click_events retention policy");
    }

    // Operator credentials (debt 26): the actuator tiers must be reachable by an
    // infrastructure operator — BasicAuth credentials injected via environment.
    String operatorUsername = environment.getProperty("app.security.operator.username");
    String operatorPassword = environment.getProperty("app.security.operator.password");
    if (operatorUsername == null || operatorUsername.isBlank()) {
      errors.add("security.operator.username (OPERATOR_USERNAME) is required in production");
    } else if ("operator".equalsIgnoreCase(operatorUsername)
        || "admin".equalsIgnoreCase(operatorUsername)) {
      errors.add("security.operator.username must not be a guessable default (operator/admin)");
    }
    if (operatorPassword == null || operatorPassword.isBlank()) {
      errors.add("security.operator.password (OPERATOR_PASSWORD) is required in production");
    } else if (operatorPassword.length() < 16) {
      errors.add("security.operator.password must be at least 16 characters");
    }

    if (!errors.isEmpty()) {
      String msg =
          "Production configuration validation failed:\n  - " + String.join("\n  - ", errors);
      throw new IllegalStateException(msg);
    }
  }

  private boolean isDefaultJwtSecret(String secret) {
    // Check against known default dev secret
    return "9a4f2c8d3b7a1e6f4c5d8e9a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c".equals(secret);
  }
}
