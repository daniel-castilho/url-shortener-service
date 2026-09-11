package ca.tyny.urlshortener.infra.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Integration test for {@link ProdConfigValidator}.
 *
 * <p>Instantiates the validator against a {@link MockEnvironment} and invokes {@code validate()}
 * directly, so the fail-fast checks can be exercised without booting a full production Spring
 * context. Covers: missing / short / default-dev JWT secret, a fully valid production config, and
 * non-prod profiles being skipped.
 */
@DisplayName("ProdConfigValidator Integration Tests")
class ProdConfigValidatorIT {

  @Test
  @DisplayName("Non-prod profile skips validation even with missing secret")
  void skipsValidationOutsideProd() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("default");
    ProdConfigValidator validator = new ProdConfigValidator(env);

    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Prod profile with missing JWT secret fails fast")
  void failsOnMissingSecret() {
    ProdConfigValidator validator = new ProdConfigValidator(currentProdEnv(null));

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.jwt.secret is required in production");
  }

  @Test
  @DisplayName("Prod profile with short JWT secret fails fast")
  void failsOnShortSecret() {
    ProdConfigValidator validator = new ProdConfigValidator(currentProdEnv("short"));

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.jwt.secret must be at least 32 characters");
  }

  @Test
  @DisplayName("Prod profile with the default dev JWT secret fails fast")
  void failsOnDefaultDevSecret() {
    ProdConfigValidator validator =
        new ProdConfigValidator(
            currentProdEnv("9a4f2c8d3b7a1e6f4c5d8e9a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c"));

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.jwt.secret appears to be the default development value");
  }

  @Test
  @DisplayName("Prod profile with a strong overridden JWT secret and complete config passes")
  void passesWithCompleteProdConfig() {
    ProdConfigValidator validator =
        new ProdConfigValidator(currentProdEnv("this-is-a-strong-32-char-plus-production-secret!"));

    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Prod profile without operator credentials fails fast (debt 26)")
  void failsOnMissingOperatorCredentials() {
    MockEnvironment env =
        currentProdEnv("this-is-a-strong-32-char-plus-production-secret!")
            .withProperty("app.security.operator.username", "ops-oncall")
            .withProperty("app.security.operator.password", "");
    ProdConfigValidator validator = new ProdConfigValidator(env);

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("security.operator.password (OPERATOR_PASSWORD) is required");
  }

  @Test
  @DisplayName("Prod profile with a weak operator password fails fast")
  void failsOnWeakOperatorPassword() {
    MockEnvironment env =
        currentProdEnv("this-is-a-strong-32-char-plus-production-secret!")
            .withProperty("app.security.operator.username", "ops-oncall")
            .withProperty("app.security.operator.password", "short");
    ProdConfigValidator validator = new ProdConfigValidator(env);

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("security.operator.password must be at least 16 characters");
  }

  @Test
  @DisplayName("Prod profile with a guessable operator username fails fast")
  void failsOnGuessableOperatorUsername() {
    MockEnvironment env =
        currentProdEnv("this-is-a-strong-32-char-plus-production-secret!")
            .withProperty("app.security.operator.username", "operator")
            .withProperty("app.security.operator.password", "a-strong-16-char-pass!");
    ProdConfigValidator validator = new ProdConfigValidator(env);

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("security.operator.username must not be a guessable default");
  }

  private MockEnvironment currentProdEnv(String jwtSecret) {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.mongodb.uri", "mongodb://mongo.internal:27017/db")
            .withProperty("spring.data.redis.host", "redis.internal")
            .withProperty("rate-limiter.trusted-proxy-cidrs", "10.0.0.0/8")
            .withProperty("management.otlp.tracing.endpoint", "http://otel-collector.internal:4318")
            .withProperty("app.analytics.retention-days", "90")
            .withProperty("app.security.operator.username", "ops-oncall")
            .withProperty("app.security.operator.password", "a-strong-16-char-pass!");
    if (jwtSecret != null) {
      env.withProperty("app.jwt.secret", jwtSecret);
    }
    env.setActiveProfiles("prod");
    return env;
  }
}
