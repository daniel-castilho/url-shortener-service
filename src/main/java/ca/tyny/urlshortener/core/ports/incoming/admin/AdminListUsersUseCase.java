package ca.tyny.urlshortener.core.ports.incoming.admin;

import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.UserAdminItem;

/**
 * Admin use case: list users for the product administration surface.
 *
 * <p>The caller identity (email + role) is passed explicitly — the port knows nothing about Spring
 * Security. Implementations must enforce the ADMIN role (non-admin caller → {@code
 * ForbiddenException}).
 */
public interface AdminListUsersUseCase {

  /**
   * Lists users, newest first (stable {@code createdAt DESC, id DESC} cursor pagination),
   * optionally filtered by an email prefix. {@code role} on each item is the live truth from the
   * admin email list, not the token claim.
   *
   * @param callerEmail the authenticated caller's email
   * @param callerRole the authenticated caller's role ({@code "ADMIN"} or {@code "USER"})
   * @param emailPrefix optional email prefix filter ({@code null} or blank = no filter)
   * @param request pagination request (limit + cursor)
   * @throws ca.tyny.urlshortener.core.exception.ForbiddenException when the caller is not an admin
   */
  PageResult<UserAdminItem> list(
      String callerEmail, String callerRole, String emailPrefix, PageRequest request);
}
