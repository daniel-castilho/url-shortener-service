package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when a refresh token is missing, malformed, expired or otherwise invalid during {@code
 * POST /api/v1/auth/refresh}. Mapped to HTTP 401 by {@link
 * ca.tyny.urlshortener.infra.adapter.input.rest.advice.GlobalExceptionHandler}.
 *
 * <p>Distinct from {@link IllegalArgumentException} so the REST adapter can distinguish a genuine
 * refresh failure (401 + clear both auth cookies) from a client-side request bug (400), and so the
 * web frontend's single-flight refresh coordinator can hard-logout on terminal refresh failure.
 */
public class InvalidRefreshTokenException extends RuntimeException {

  public InvalidRefreshTokenException(String message) {
    super(message);
  }
}
