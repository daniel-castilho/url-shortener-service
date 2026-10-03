package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.infra.config.properties.ShortenerProperties;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Resolves the public origin used to build the {@code shortUrl} field in API responses (shorten,
 * list, detail, PATCH, admin).
 *
 * <p>Resolution order for a given link:
 *
 * <ol>
 *   <li><b>Custom-domain binding wins</b> — a link bound to a verified custom host resolves to
 *       {@code https://<domain>}: short links are HTTPS-only (product security rule) and live on
 *       the domain they were bound to.
 *   <li><b>Explicitly configured origin</b> — {@code app.shortener.public-base-url} ({@code
 *       APP_PUBLIC_BASE_URL}), the preferred and production-required setting.
 *   <li><b>Request-derived fallback</b> — the current request's scheme/host/port. Only correct when
 *       clients reach the service directly (local dev); behind a proxy it leaks the bind origin and
 *       scheme unless forwarded-header processing is enabled by the operator.
 * </ol>
 *
 * <p>Forwarded headers ({@code X-Forwarded-*}) are deliberately NOT trusted here: honoring them
 * implicitly would let any direct caller forge the {@code shortUrl} origin. Operators who prefer
 * request-derived origins behind a proxy must enable forwarded-header processing explicitly with a
 * proxy-scoped trust boundary (e.g. {@code server.forward-headers-strategy: native} with Tomcat
 * internal-proxies restricted to the edge CIDR) — see docs/release-runbook.md.
 */
public class ShortLinkBaseUrlResolver {

  private final String publicBaseUrl;

  public ShortLinkBaseUrlResolver(ShortenerProperties properties) {
    this.publicBaseUrl = normalizeConfigured(properties.publicBaseUrl());
  }

  /** Public origin for the given link; a custom-domain binding wins over the default origin. */
  public String baseFor(ShortUrl link) {
    if (link != null && link.domain() != null && !link.domain().isBlank()) {
      return "https://" + link.domain();
    }
    return defaultBase();
  }

  /** Public origin for default-host links (no custom-domain binding). */
  public String defaultBase() {
    if (publicBaseUrl != null) {
      return publicBaseUrl;
    }
    return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
  }

  private static String normalizeConfigured(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    if (trimmed.isBlank() || !trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must be an absolute origin starting with https:// "
              + "(http:// only for local development), got: '"
              + value
              + "'");
    }
    return trimmed;
  }
}
