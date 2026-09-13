package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.ShortUrl;
import java.util.Optional;

/**
 * Outbound port that defines the contract for short-URL persistence.
 *
 * <p>An abstraction that keeps the application core (domain layer) independent of infrastructure
 * details (which database is used).
 *
 * <p>Follows the Ports &amp; Adapters pattern (Clean Architecture): - Port: this abstraction is
 * database-agnostic - Adapter: concrete implementation (e.g. MongoUrlRepository)
 *
 * <p>Responsibilities: - Define URL persistence operations - Stay agnostic about the specific data
 * store - Be testable (mock implementations can be created easily)
 *
 * @author URL Shortener Team
 */
public interface UrlRepositoryPort {

  /**
   * Persists a shortened URL.
   *
   * @param shortUrl the shortened URL to save
   * @throws IllegalArgumentException if the data is invalid
   * @throws RuntimeException (or specific subclasses) on a persistence error
   */
  void save(ShortUrl shortUrl);

  /**
   * Retrieves a shortened URL by its unique identifier.
   *
   * @param id the unique identifier of the shortened URL
   * @return Optional containing the URL if found, empty otherwise
   * @throws RuntimeException (or specific subclasses) when the query fails
   */
  Optional<ShortUrl> findById(String id);

  /**
   * Checks whether a shortened URL exists by its identifier.
   *
   * @param id the unique identifier
   * @return true if it exists, false otherwise
   */
  boolean existsById(String id);

  /**
   * Atomically increments the click counter of a shortened URL by 1.
   *
   * @param id the unique identifier of the shortened URL
   */
  default void incrementClickCount(String id) {
    incrementClickCount(id, 1L);
  }

  /**
   * Atomically increments the click counter of a shortened URL.
   *
   * <p>The implementation must use a storage-side atomic increment (e.g. {@code $inc} in MongoDB),
   * never a read-then-write, so increments are not lost under concurrency. If the code does not
   * exist, it is a no-op.
   *
   * @param id the unique identifier of the shortened URL
   * @param delta how much to add to the counter (&gt; 0)
   */
  void incrementClickCount(String id, long delta);
}
