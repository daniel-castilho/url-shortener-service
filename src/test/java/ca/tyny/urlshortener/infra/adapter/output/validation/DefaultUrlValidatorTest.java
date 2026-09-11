package ca.tyny.urlshortener.infra.adapter.output.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.tyny.urlshortener.core.exception.InvalidDestinationException;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import ca.tyny.urlshortener.infra.config.properties.UrlValidationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultUrlValidator Tests")
class DefaultUrlValidatorTest {

  private UrlValidator createValidator(
      boolean allowHttp, boolean blockPrivateIps, int dnsTimeoutMs) {
    // No-op metrics: the SSRF-blocked counter is asserted in the ITs, not in these unit tests.
    MetricsPort noopMetrics =
        new MetricsPort() {
          @Override
          public void recordUrlShortened() {}

          @Override
          public void recordCacheHit() {}

          @Override
          public void recordCacheMiss() {}

          @Override
          public void recordBloomFilterRejection() {}

          @Override
          public void recordIdGeneration(java.time.Duration duration) {}

          @Override
          public void recordUrlRetrieval(java.time.Duration duration) {}

          @Override
          public void recordUrlExpired() {}

          @Override
          public void recordMigrationApplied() {}

          @Override
          public void recordMigrationFailed() {}

          @Override
          public void recordSsrfBlocked() {}
        };
    return new DefaultUrlValidator(
        new UrlValidationProperties(allowHttp, 2000, blockPrivateIps, 300), noopMetrics);
  }

  @Test
  @DisplayName("Rejects HTTP when allowHttp is false")
  void rejectsHttpWhenDisabled() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("http://example.com"))
        .isInstanceOf(InvalidDestinationException.class)
        .hasMessageContaining("HTTP URLs are not allowed");
  }

  @Test
  @DisplayName("Rejects URL without scheme")
  void rejectsUrlWithoutScheme() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("example.com"))
        .isInstanceOf(InvalidDestinationException.class)
        .hasMessageContaining("must use http:// or https://");
  }

  @Test
  @DisplayName("Rejects malformed URL format")
  void rejectsInvalidFormat() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("not-a-url"))
        .isInstanceOf(InvalidDestinationException.class)
        .hasMessageContaining("must use http:// or https://");
  }

  @Test
  @DisplayName("Rejects invalid host format (spaces)")
  void rejectsInvalidHost() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("https://invalid host.com"))
        .isInstanceOf(InvalidDestinationException.class);
  }

  @Test
  @DisplayName("Rejects URL with userinfo (credentials)")
  void rejectsUserInfo() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("https://user:pass@example.com"))
        .isInstanceOf(InvalidDestinationException.class)
        .hasMessageContaining("user credentials");
  }

  @Test
  @DisplayName("Rejects URL with userinfo (email-like)")
  void rejectsUserInfoEmail() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("https://user@example.com"))
        .isInstanceOf(InvalidDestinationException.class)
        .hasMessageContaining("user credentials");
  }

  @Test
  @DisplayName("Valid HTTPS URL structure passes format validation")
  void allowsValidHttpsStructure() {
    UrlValidator validator = createValidator(false, true, 2000);

    // The URL structure is valid; DNS resolution is attempted but not mocked here
    // The validator will attempt DNS resolution which may succeed or fail
    // We just verify format validation passes
    var result = ((DefaultUrlValidator) validator).doValidate("https://example.com");
    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("Allows HTTP when allowHttp is true")
  void allowsHttpWhenEnabled() {
    UrlValidator validator = createValidator(true, true, 2000);

    var result = ((DefaultUrlValidator) validator).doValidate("http://example.com");
    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("Rejects invalid host format (special chars)")
  void rejectsInvalidHostSpecialChars() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("https://invalid@host.com"))
        .isInstanceOf(InvalidDestinationException.class);
  }

  @Test
  @DisplayName("Rejects IPv6 loopback literal as private/internal IP")
  void rejectsIpv6LoopbackLiteral() {
    UrlValidator validator = createValidator(false, true, 2000);

    var result = ((DefaultUrlValidator) validator).doValidate("https://[::1]/path");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).contains("private/internal IP");
  }

  @Test
  @DisplayName("Rejects invalid scheme")
  void rejectsInvalidScheme() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("ftp://example.com"))
        .isInstanceOf(InvalidDestinationException.class)
        .hasMessageContaining("http:// or https://");
  }

  @Test
  @DisplayName("Rejects empty host")
  void rejectsEmptyHost() {
    UrlValidator validator = createValidator(false, true, 2000);

    assertThatThrownBy(() -> validator.validate("https://"))
        .isInstanceOf(InvalidDestinationException.class)
        .hasMessageContaining("authority");
  }
}
