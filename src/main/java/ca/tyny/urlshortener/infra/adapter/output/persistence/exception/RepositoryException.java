package ca.tyny.urlshortener.infra.adapter.output.persistence.exception;

/**
 * Domain exception thrown when errors occur in the persistence layer. Encapsulates MongoDB- or
 * database-specific exceptions so that infrastructure details never leak into the application core.
 *
 * <p>Follows the infrastructure isolation pattern proposed by Clean Architecture.
 */
public class RepositoryException extends RuntimeException {

  /**
   * Constructor with a descriptive message.
   *
   * @param message description of the error that occurred
   */
  public RepositoryException(String message) {
    super(message);
  }

  /**
   * Constructor with a message and root cause (cause chaining). Allows tracing the original MongoDB
   * or database exception.
   *
   * @param message description of the error that occurred
   * @param cause the original exception (e.g. MongoException)
   */
  public RepositoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
