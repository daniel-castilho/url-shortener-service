package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when a destination URL fails SSRF or format validation.
 * Results in HTTP 400 Bad Request.
 */
public class InvalidDestinationException extends IllegalArgumentException {

    public InvalidDestinationException(String message) {
        super(message);
    }

    public InvalidDestinationException(String message, Throwable cause) {
        super(message, cause);
    }
}