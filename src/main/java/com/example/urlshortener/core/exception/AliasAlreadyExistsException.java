package com.example.urlshortener.core.exception;

public class AliasAlreadyExistsException extends RuntimeException {
    public AliasAlreadyExistsException(String alias) {
        super("Alias '" + alias + "' is already in use.");
    }
}
