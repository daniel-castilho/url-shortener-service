package ca.tyny.urlshortener.infra.adapter.output.persistence.mapper;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class ShortUrlMapper {

    public ShortUrlEntity toPersistence(ShortUrl domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain object cannot be null");
        }

        String urlHash = sha256Hex(domain.originalUrl());

        return new ShortUrlEntity(
                domain.id(),
                domain.originalUrl(),
                urlHash,
                domain.createdAt(),
                domain.userId(),
                domain.isCustomAlias(),
                domain.clickCount());
    }

    public ShortUrl toDomain(ShortUrlEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity object cannot be null");
        }

        return new ShortUrl(
                entity.getId(),
                entity.getOriginalUrl(),
                entity.getCreatedAt(),
                entity.getUserId(),
                entity.isCustomAlias(),
                entity.getClickCount());
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
