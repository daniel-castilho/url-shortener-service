package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated user id from the security context.
 *
 * <p>Shared by the REST adapters that need owner-scoping; the framework concern (current
 * authentication) stays entirely in {@code infra/}.
 */
@Component
public class CurrentUserResolver {

    private final UserRepositoryPort userRepository;

    public CurrentUserResolver(UserRepositoryPort userRepository) {
        this.userRepository = userRepository;
    }

    public String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("Unauthenticated");
        }
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .map(User::id)
                .orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }
}