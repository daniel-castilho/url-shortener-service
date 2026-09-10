package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.model.CustomDomain;
import java.util.List;

/**
 * Inbound port for custom-domain management (Phase C — branded domains).
 *
 * <p>Owner-guarded at the application layer: a user only manages domains they claimed.
 */
public interface CustomDomainUseCase {

  /** Claims a host for the user, issuing a DNS verification token (status PENDING). */
  CustomDomain claim(String userId, String host);

  /** Lists the domains claimed by {@code userId}. */
  List<CustomDomain> listForOwner(String userId);

  /** Removes the user's claim for {@code host}. */
  void delete(String userId, String host);

  /**
   * Re-triggers verification: issues a fresh token and resets the domain to PENDING so the DNS
   * health check can promote it again.
   */
  CustomDomain requestReVerification(String userId, String host);
}
