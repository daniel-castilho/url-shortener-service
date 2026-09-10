package ca.tyny.urlshortener.core.model;

/**
 * Lifecycle of a claimed custom domain.
 *
 * <ul>
 *   <li>{@link #PENDING} — claimed, verification record not yet seen.
 *   <li>{@link #VERIFIED} — the DNS verification record was found (transient state).
 *   <li>{@link #ACTIVE} — DNS record present; the domain is usable for shorten/resolve.
 *   <li>{@link #FAILED} — verification record missing or expired; the user can re-trigger.
 * </ul>
 */
public enum DomainStatus {
  PENDING,
  VERIFIED,
  ACTIVE,
  FAILED
}
