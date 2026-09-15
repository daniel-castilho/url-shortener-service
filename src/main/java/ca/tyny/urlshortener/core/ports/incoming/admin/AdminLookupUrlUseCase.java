package ca.tyny.urlshortener.core.ports.incoming.admin;

import ca.tyny.urlshortener.core.model.AdminUrlLookup;

/**
 * Admin use case: resolve any short code globally (any owner), including archived links.
 *
 * <p>The short code IS the document id (the redirect path {@code GET /{id}} resolves it via {@code
 * findById}), so this reuses the existing {@code LinkQueryPort.findById} — no new repository
 * method. Implementations must enforce the ADMIN role (non-admin caller → {@code
 * ForbiddenException}).
 */
public interface AdminLookupUrlUseCase {

  /**
   * Looks up a short URL by its code regardless of ownership.
   *
   * @param callerRole the authenticated caller's role ({@code "ADMIN"} or {@code "USER"})
   * @param code the short URL code (document id)
   * @throws ca.tyny.urlshortener.core.exception.ForbiddenException when the caller is not an admin
   * @throws ca.tyny.urlshortener.core.exception.UrlNotFoundException when the code is unknown
   */
  AdminUrlLookup lookup(String callerRole, String code);
}
