package ca.tyny.urlshortener.core.model;

import java.time.Instant;

/**
 * Value returned from / written to the URL cache.
 *
 * @param originalUrl the long destination URL
 * @param expiresAt instant at which the link expires ({@code null} = never)
 * @param domain the custom host this link is bound to ({@code null} = default host only); kept in
 *     the cache so the redirect can enforce the binding without a DB hit
 */
public record CachedUrlValue(String originalUrl, Instant expiresAt, String domain) {

  public CachedUrlValue(String originalUrl, Instant expiresAt) {
    this(originalUrl, expiresAt, null);
  }

  public boolean isExpired(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }
}
