package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when a required user does not exist. Mapped to HTTP 404 by {@link
 * ca.tyny.urlshortener.infra.adapter.input.rest.advice.GlobalExceptionHandler}.
 */
public class UserNotFoundException extends RuntimeException {

  public UserNotFoundException(String message) {
    super(message);
  }
}
