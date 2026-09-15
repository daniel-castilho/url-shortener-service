package ca.tyny.urlshortener.core.ports.incoming.admin;

/**
 * Admin use case: force-archive any link regardless of ownership.
 *
 * <p>Same {@code deletedAt} soft-delete semantics as the owner archive (idempotent — 204 on an
 * already-archived link) plus the cache eviction, but scoped to an arbitrary link id on the product
 * administration surface. Implementations must enforce the ADMIN role (non-admin caller → {@code
 * ForbiddenException}).
 */
public interface AdminForceArchiveLinkUseCase {

  /**
   * Archives a link by id, no ownership check.
   *
   * @param callerRole the authenticated caller's role ({@code "ADMIN"} or {@code "USER"})
   * @param id the short URL code (document id)
   * @throws ca.tyny.urlshortener.core.exception.ForbiddenException when the caller is not an admin
   * @throws ca.tyny.urlshortener.core.exception.UrlNotFoundException when the link does not exist
   */
  void forceArchiveLink(String callerRole, String id);
}
