package ca.tyny.urlshortener.core.model;

import java.time.Instant;

/**
 * A custom host claimed by a user to host short links (e.g. {@code links.marca.co}).
 *
 * <p>Ownership is proven via a DNS verification record: a {@link #verificationToken()} is returned
 * on claim and the domain enters {@link DomainStatus#ACTIVE} once the record is observed by the
 * scheduled DNS health check. Only ACTIVE domains are usable for shortening and host-aware redirect
 * resolution (option B — a link stores the host it was created for in {@code short_url.domain}).
 */
public record CustomDomain(
    String host, String userId, DomainStatus status, String verificationToken, Instant createdAt) {

  /** Copy of this domain with a new lifecycle status. */
  public CustomDomain withStatus(DomainStatus newStatus) {
    return new CustomDomain(host, userId, newStatus, verificationToken, createdAt);
  }

  /** Copy of this domain with a new verification token (re-trigger). */
  public CustomDomain withVerificationToken(String newToken) {
    return new CustomDomain(host, userId, status, newToken, createdAt);
  }

  /** A domain is usable when its DNS record has been observed and is healthy. */
  public boolean isActive() {
    return status == DomainStatus.ACTIVE;
  }
}
