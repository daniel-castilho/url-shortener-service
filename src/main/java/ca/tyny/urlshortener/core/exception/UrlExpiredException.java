package ca.tyny.urlshortener.core.exception;

public class UrlExpiredException extends RuntimeException {
    public UrlExpiredException(String id) {
        super("URL has expired for ID: " + id);
    }
}