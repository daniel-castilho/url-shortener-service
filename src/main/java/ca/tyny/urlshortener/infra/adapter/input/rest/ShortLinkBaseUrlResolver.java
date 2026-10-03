package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.infra.config.properties.ShortenerProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
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
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    if (trimmed.isBlank()) {
      return null; // empty/blank means unset, not an error
    }

    // Parse as URI to validate it's a pure origin (scheme + host[:port] only)
    URI uri;
    try {
      uri = new URI(trimmed); // use trimmed (no trailing slashes)
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must be a valid absolute URI, got: '" + value + "'");
    }

    // Must have scheme and host
    if (uri.getScheme() == null || uri.getScheme().isBlank()) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must have a scheme (https://), got: '" + value + "'");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must have a host, got: '" + value + "'");
    }

    // Must be an origin: no path, no query, no fragment
    if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must be a bare origin (no path), got: '" + value + "'");
    }
    if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must not contain a query string, got: '" + value + "'");
    }
    if (uri.getFragment() != null && !uri.getFragment().isBlank()) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must not contain a fragment, got: '" + value + "'");
    }
    if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url must not contain user info, got: '" + value + "'");
    }

    // Scheme validation: https required (http allowed only for local dev; prod enforced by
    // ProdConfigValidator)
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !"http".equals(scheme)) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url scheme must be https (or http for local dev), got: '"
              + scheme
              + "'");
    }

    // Host validation: not an IP literal for https (security), valid hostname format
    String host = uri.getHost();
    if ("https".equals(scheme) && isIpLiteral(host)) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url with https must use a hostname, not an IP literal, got: '"
              + value
              + "'");
    }
    if (!isValidHostname(host)) {
      throw new IllegalArgumentException(
          "app.shortener.public-base-url has an invalid hostname, got: '" + value + "'");
    }

    // Rebuild canonical origin: scheme://host[:port]
    StringBuilder canonical = new StringBuilder();
    canonical.append(scheme).append("://").append(host);
    int port = uri.getPort();
    if (port != -1 && port != getDefaultPort(scheme)) {
      canonical.append(':').append(port);
    }
    return canonical.toString();
  }

  private static boolean isIpLiteral(String host) {
    if (host == null) return false;
    // IPv4
    if (host.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
      String[] parts = host.split("\\.");
      for (String part : parts) {
        int n = Integer.parseInt(part);
        if (n < 0 || n > 255) return false;
      }
      return true;
    }
    // IPv6 literal (simplified check for bracket notation)
    if (host.startsWith("[") && host.endsWith("]")) {
      return true;
    }
    return false;
  }

  private static boolean isValidHostname(String host) {
    if (host == null || host.isBlank() || host.length() > 253) return false;
    // RFC 1123 / RFC 952: labels of 1-63 chars, alphanum + hyphen, no leading/trailing hyphen
    String[] labels = host.split("\\.");
    for (String label : labels) {
      if (label.isEmpty() || label.length() > 63) return false;
      if (!label.matches("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$")) return false;
    }
    return true;
  }

  private static int getDefaultPort(String scheme) {
    return "https".equals(scheme) ? 443 : 80;
  }
}
