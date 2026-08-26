package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Port for token generation and validation operations.
 * This interface defines the contract for JWT token management.
 */
public interface TokenPort {

    /**
     * Generate a token for the given email.
     * 
     * @param email the email to generate a token for
     * @return the generated token
     */
    String generateToken(String email);

    /**
     * Generate a refresh token for the given email.
     * 
     * @param email the email to generate a refresh token for
     * @return the generated refresh token
     */
    String generateRefreshToken(String email);

    /**
     * Validate a token.
     * 
     * @param token the token to validate
     * @return true if the token is valid, false otherwise
     */
    boolean validateToken(String token);

    /**
     * Extract the username (email) from a token.
     * 
     * @param token the token to extract the username from
     * @return the username (email) from the token
     */
    String getUsernameFromToken(String token);
}
