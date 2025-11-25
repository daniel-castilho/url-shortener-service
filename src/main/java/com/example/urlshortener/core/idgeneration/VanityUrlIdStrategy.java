package com.example.urlshortener.core.idgeneration;

import com.example.urlshortener.core.model.User;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import com.example.urlshortener.core.ports.outgoing.UserRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VanityUrlIdStrategy implements UrlIdGenerationStrategy {

    private final UserRepositoryPort userRepository;
    private final UrlRepositoryPort urlRepository;

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
