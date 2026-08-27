package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when an invalid expiry configuration is provided (e.g. TTL exceeds the server-side cap).
 */
public class InvalidExpiryException extends RuntimeException {

    public InvalidExpiryException(String message) {
        super(message);
    }
}