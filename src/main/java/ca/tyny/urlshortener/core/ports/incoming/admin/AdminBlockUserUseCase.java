package ca.tyny.urlshortener.core.ports.incoming.admin;

/**
 * Admin use case: block a user account (writes {@code blocked=true}).
 *
 * <p>The caller identity (email + role) is passed explicitly — the port knows nothing about Spring
 * Security. Implementations enforce the ADMIN role, reject self-block, and return 404 semantics for
 * unknown users.
 */
public interface AdminBlockUserUseCase {

  /**
   * Blocks the given user.
   *
   * @param callerEmail the authenticated caller's email
   * @param callerRole the authenticated caller's role ({@code "ADMIN"} or {@code "USER"})
   * @param userId the user id to block
   * @throws ca.tyny.urlshortener.core.exception.ForbiddenException when the caller is not an admin
   * @throws ca.tyny.urlshortener.core.exception.UserNotFoundException when the target user does not
   *     exist
   * @throws IllegalArgumentException when the caller tries to block themselves (self-block → 400)
   */
  void block(String callerEmail, String callerRole, String userId);
}
