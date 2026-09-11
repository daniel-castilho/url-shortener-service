package ca.tyny.urlshortener.infra.config.properties;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed configuration for security hardening (prefix {@code app.security}). */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
    @DefaultValue("true") boolean actuatorEnabled,
    Actuator actuator,
    Swagger swagger,
    Operator operator) {

  public record Actuator(
      @DefaultValue("true") boolean enabled,
      @DefaultValue({"health/liveness", "health/readiness", "info"}) Set<String> publicEndpoints) {}

  public record Swagger(
      @DefaultValue("false") boolean enabled, @DefaultValue("") List<String> allowedIPs) {}

  /**
   * Infrastructure operator account for the actuator tiers (debt 26). Injected via environment
   * ({@code OPERATOR_USERNAME} / {@code OPERATOR_PASSWORD}) — never hardcoded. Empty by default:
   * outside {@code prod} the operator is simply absent and actuator tiers keep returning 401 to
   * anonymous callers. When set, the account authenticates via HTTP Basic and grants {@code
   * ROLE_OPERATOR}: health, metrics and prometheus (+ circuit breakers), but never env, beans or
   * the index (minimum privilege).
   */
  public record Operator(@DefaultValue("") String username, @DefaultValue("") String password) {

    public boolean isConfigured() {
      return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
  }
}
