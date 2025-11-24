package com.example.urlshortener.core.exception;

public class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String id) {
        super("URL not found for ID: " + id);
    }
}
