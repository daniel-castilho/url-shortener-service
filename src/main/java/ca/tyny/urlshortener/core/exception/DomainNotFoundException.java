package ca.tyny.urlshortener.core.exception;

public class DomainNotFoundException extends RuntimeException {

    public DomainNotFoundException(String host) {
        super("Custom domain not found: " + host);
    }
}