package com.example.urlshortener.core.ports.outgoing;

import com.example.urlshortener.core.model.User;

import java.util.Optional;

/**
 * Port for User repository operations.
 * This interface defines the contract for user persistence.
 */
public interface UserRepositoryPort {

    /**
     * Save or update a user
     * 
     * @param user the user to save
     * @return the saved user
     */
    User save(User user);

    /**
     * Find a user by ID
     * 
     * @param id the user ID
     * @return Optional containing the user if found
     */
    Optional<User> findById(String id);

    /**
     * Find a user by email
     * 
     * @param email the user email
     * @return Optional containing the user if found
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if a user exists by email
     * 
     * @param email the email to check
     * @return true if user exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Delete a user by ID
     * 
     * @param id the user ID
     */
    void deleteById(String id);
}
