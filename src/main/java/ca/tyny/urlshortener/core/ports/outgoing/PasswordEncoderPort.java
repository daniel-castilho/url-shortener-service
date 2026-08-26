package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Port for password encoding operations.
 * This interface defines the contract for password hashing.
 */
public interface PasswordEncoderPort {

    /**
     * Encode a raw password.
     * 
     * @param rawPassword the raw password to encode
     * @return the encoded password
     */
    String encode(String rawPassword);

    /**
     * Check if a raw password matches an encoded password.
     * 
     * @param rawPassword     the raw password to check
     * @param encodedPassword the encoded password to compare against
     * @return true if the passwords match, false otherwise
     */
    boolean matches(String rawPassword, String encodedPassword);
}
