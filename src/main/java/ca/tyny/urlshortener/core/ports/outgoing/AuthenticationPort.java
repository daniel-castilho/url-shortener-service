package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Port for authentication operations.
 * This interface defines the contract for user authentication.
 */
public interface AuthenticationPort {

    /**
     * Authenticate a user with email and password.
     * 
     * @param email    the user's email
     * @param password the user's password
     * @throws IllegalArgumentException if authentication fails
     */
    void authenticate(String email, String password);
}
