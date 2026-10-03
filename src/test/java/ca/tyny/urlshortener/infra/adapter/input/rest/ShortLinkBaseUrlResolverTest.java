package ca.tyny.urlshortener.infra.adapter.input.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.infra.config.properties.ShortenerProperties;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayName("ShortLinkBaseUrlResolver Tests")
class ShortLinkBaseUrlResolverTest {

  private static final String TEST_URL = "https://www.example.com/long";
  private static final String TEST_ID = "abc123";

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void stubRequestContext(String host, int port) {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    servletRequest.setScheme("http");
    servletRequest.setServerName(host);
    servletRequest.setServerPort(port);
    servletRequest.setRequestURI("/");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
  }

  private ShortLinkBaseUrlResolver resolver(String publicBaseUrl) {
    return new ShortLinkBaseUrlResolver(new ShortenerProperties(7, 31_536_000L, publicBaseUrl));
  }

  @Test
  @DisplayName("Configured public base URL is used verbatim for default-host links")
  void configuredBaseWinsWhenSet() {
    assertThat(resolver("https://short.example.com").baseFor(defaultHostLink()))
        .isEqualTo("https://short.example.com");
  }

  @Test
  @DisplayName("Custom-domain binding wins over the configured public base URL and is HTTPS")
  void customDomainBindingWins() {
    ShortUrl link = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now()).withDomain("go.acme.io");

    assertThat(resolver("https://short.example.com").baseFor(link)).isEqualTo("https://go.acme.io");
  }

  @Test
  @DisplayName("Unset public base URL falls back to the current request origin")
  void fallsBackToRequestWhenUnset() {
    stubRequestContext("localhost", 18080);

    assertThat(resolver("").baseFor(defaultHostLink())).isEqualTo("http://localhost:18080");
  }

  @Test
  @DisplayName("Configured base URL is normalized (trimmed, trailing slashes stripped)")
  void normalizesConfiguredBase() {
    assertThat(resolver("  https://short.example.com///  ").baseFor(defaultHostLink()))
        .isEqualTo("https://short.example.com");
  }

  @Test
  @DisplayName("A configured base URL without a scheme fails fast at startup")
  void rejectsSchemelessBase() {
    assertThatThrownBy(() -> resolver("short.example.com"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public-base-url");
  }

  @Test
  @DisplayName("A null link resolves to the default base")
  void nullLinkUsesDefaultBase() {
    stubRequestContext("localhost", 8080);

    assertThat(resolver("").baseFor(null)).isEqualTo("http://localhost:8080");
    assertThat(resolver("https://short.example.com").baseFor(null))
        .isEqualTo("https://short.example.com");
  }

  private ShortUrl defaultHostLink() {
    return new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
  }
}
