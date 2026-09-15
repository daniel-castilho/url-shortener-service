package ca.tyny.urlshortener.core.ports.incoming.admin;

import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;

/**
 * Admin use case: read-only inspection of a user's links (including archived ones).
 *
 * <p>Same contract as the owner list ({@code createdAt DESC, id DESC} cursor pagination, archived
 * links included with their {@code deletedAt} visible), but scoped to an arbitrary {@code userId}
 * and offered through the product administration surface. Implementations must enforce the ADMIN
 * role (non-admin caller → {@code ForbiddenException}).
 */
public interface AdminListUserUrlsUseCase {

  /**
   * Lists the links owned by the given user, newest first.
   *
   * @param callerRole the authenticated caller's role ({@code "ADMIN"} or {@code "USER"})
   * @param userId the owner whose links are inspected
   * @param request pagination request (limit + cursor)
   * @throws ca.tyny.urlshortener.core.exception.ForbiddenException when the caller is not an admin
   * @throws ca.tyny.urlshortener.core.exception.UserNotFoundException when the user does not exist
   */
  PageResult<ShortUrl> listUserUrls(String callerRole, String userId, PageRequest request);
}
