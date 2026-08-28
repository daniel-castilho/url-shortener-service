package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when an authenticated user attempts an operation on a resource they do not own.
 * Mapped to HTTP 403 by {@link ca.tyny.urlshortener.infra.adapter.input.rest.advice.GlobalExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}