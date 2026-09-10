package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Registry of custom hosts currently usable for host-aware redirect resolution.
 *
 * <p>Only domains in {@link ca.tyny.urlshortener.core.model.DomainStatus#ACTIVE} resolve. This is a
 * cached/cheap lookup on the redirect path — an in-memory snapshot of a Redis set, refreshed
 * periodically — and must never add a slow or blocking call there.
 */
public interface CustomDomainRegistryPort {

  /** True when {@code host} is an ACTIVE (verified, usable) custom domain. */
  boolean isActiveHost(String host);

  /** Marks a host active after a successful DNS verification. */
  void markActive(String host);

  /** Marks a host inactive (verification lost or claim removed). */
  void markInactive(String host);

  /** Rebuilds the registered set from the persistence layer (self-heal). */
  void refresh();
}
