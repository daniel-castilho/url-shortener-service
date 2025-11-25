package com.example.urlshortener.core.validation;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ReservedWordsValidator {

    private static final Set<String> RESERVED_WORDS = Set.of(
            "api", "auth", "health", "admin", "swagger", "metrics", "actuator",
            "v1", "v2", "v3", "login", "register", "refresh", "logout",
            "dashboard", "profile", "billing", "settings", "users", "urls",
            "static", "public", "assets", "css", "js", "images", "img",
            "favicon", "robots", "sitemap");

    public boolean isReserved(String alias) {
        if (alias == null) {
            return false;
        }
        return RESERVED_WORDS.contains(alias.toLowerCase());
    }

    public void validate(String alias) {
        if (isReserved(alias)) {
            throw new IllegalArgumentException("The alias '" + alias + "' is reserved and cannot be used.");
        }
    }
}
