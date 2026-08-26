package ca.tyny.urlshortener.core.idgeneration;

import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class VanityUrlIdStrategy implements UrlIdGenerationStrategy {

    private final UserRepositoryPort userRepository;
    private final UrlRepositoryPort urlRepository;

    public VanityUrlIdStrategy(UserRepositoryPort userRepository, UrlRepositoryPort urlRepository) {
        this.userRepository = userRepository;
        this.urlRepository = urlRepository;
    }

    @Override
    public boolean supports(String customAlias) {
        // Suporta quando HÁ um alias customizado
        return customAlias != null && !customAlias.isBlank();
    }

    @Override
    public String generateId(String customAlias, String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("Authentication required for custom alias");
        }

        // Validate User and Plan
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!user.canCreateVanityUrls()) {
            throw new IllegalArgumentException("Plan limit reached for vanity URLs or subscription inactive");
        }

        // Validate Alias Format
        if (!customAlias.matches("^[a-zA-Z0-9-_]+$")) {
            throw new IllegalArgumentException("Invalid custom alias format");
        }

        // Validate Alias Availability
        if (urlRepository.existsById(customAlias)) {
            throw new IllegalArgumentException("Custom alias already in use");
        }

        return customAlias;
    }
}
