package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.User;
import java.util.Optional;

/**
 * Port for User repository operations. This interface defines the contract for user persistence.
 */
public interface UserRepositoryPort {

  /**
   * Save or update a user
   *
   * @param user the user to save
   * @return the saved user
   */
  User save(User user);

  /**
   * Find a user by ID
   *
   * @param id the user ID
   * @return Optional containing the user if found
   */
  Optional<User> findById(String id);

  /**
   * Find a user by email
   *
   * @param email the user email
   * @return Optional containing the user if found
   */
  Optional<User> findByEmail(String email);

  /**
   * Check if a user exists by email
   *
   * @param email the email to check
   * @return true if user exists, false otherwise
   */
  boolean existsByEmail(String email);

  /**
   * Delete a user by ID
   *
   * @param id the user ID
   */
  void deleteById(String id);

  /**
   * Atomically increments the vanity-URL quota counters for a user.
   *
   * <p>The implementation must use a server-side atomic increment (e.g. MongoDB {@code $inc}) on
   * both the monthly and total counters — never read-modify-write, which loses updates under
   * concurrency.
   *
   * @param userId the user ID
   */
  void incrementVanityUsage(String userId);

  /**
   * Returns a cursor-paginated page of users ordered by {@code createdAt DESC, id DESC} (stable
   * contract, same shape as the links list).
   *
   * @param cursor the pagination cursor ({@code null} = first page)
   * @param limit the page size (capped at {@code PageRequest.MAX_LIMIT} by the adapter)
   */
  PageResult<User> findPage(Cursor cursor, int limit);

  /**
   * Returns a cursor-paginated page of users whose email starts with the given prefix (matching is
   * case-insensitive; the prefix is regex-escaped by the adapter).
   *
   * @param emailPrefix the email prefix to match
   * @param cursor the pagination cursor ({@code null} = first page)
   * @param limit the page size (capped at {@code PageRequest.MAX_LIMIT} by the adapter)
   */
  PageResult<User> findPageByEmailPrefix(String emailPrefix, Cursor cursor, int limit);

  /**
   * Sets (or clears) the {@code blocked} flag on a user with a targeted server-side update.
   *
   * <p>Idempotent — repeating the same value is a no-op; a missing user is a no-op. The
   * implementation must never rewrite the whole document (expand-only, Rule 10).
   *
   * @param userId the user ID
   * @param blocked {@code true} to block, {@code false} to unblock
   */
  void setBlocked(String userId, boolean blocked);
}
