package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when a generated short code collides with an existing _id in the repository.
 * This is distinct from AliasAlreadyExistsException (user-supplied vanity alias conflict).
 */
public class ShortCodeCollisionException extends RuntimeException {

    private final String code;

    public ShortCodeCollisionException(String code) {
        super("Generated code '" + code + "' collides with existing _id");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
