package ca.tyny.urlshortener.core.exception;

public class AliasAlreadyExistsException extends RuntimeException {
    public AliasAlreadyExistsException(String alias) {
        super("Alias '" + alias + "' already exists. Please choose a different alias.");
    }
}
