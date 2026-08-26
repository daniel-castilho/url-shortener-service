package ca.tyny.urlshortener.core.exception;

/**
 * Thrown when a short code cannot be generated after exhausting all collision retries.
 * This is an unexpected failure — the system has too many collisions to proceed.
 */
public class CodeGenerationException extends RuntimeException {

    private final int attempts;

    public CodeGenerationException(int attempts) {
        super("Failed to generate a unique code after " + attempts + " attempts");
        this.attempts = attempts;
    }

    public int getAttempts() {
        return attempts;
    }
}
