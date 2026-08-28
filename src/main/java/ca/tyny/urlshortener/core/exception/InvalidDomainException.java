package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when a claimed domain host is syntactically invalid, is the default host, or
 * otherwise rejected by business rules. Mapped to HTTP 400.
 */
public class InvalidDomainException extends RuntimeException {

    public InvalidDomainException(String message) {
        super(message);
    }
}