package ca.tyny.urlshortener.infra.adapter.output.persistence.mapper;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.UtmParams;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.UtmParamsEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ShortUrlMapper {

    public ShortUrlEntity toPersistence(ShortUrl domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain object cannot be null");
        }

        String urlHash = sha256Hex(domain.originalUrl());
        UtmParamsEntity utmEntity = toUtmEntity(domain.utm());

        return new ShortUrlEntity(
                domain.id(),
                domain.originalUrl(),
                sha256Hex(domain.originalUrl()),
                domain.createdAt(),
                domain.userId(),
                domain.isCustomAlias(),
                domain.clickCount(),
                domain.expiresAt(),
                domain.title(),
                domain.tags(),
                utmEntity,
                domain.deletedAt());
    }

    public ShortUrl toDomain(ShortUrlEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity object cannot be null");
        }

        UtmParams utm = entity.getUtm() != null
                ? new UtmParams(entity.getUtm().getSource(), entity.getUtm().getMedium(),
                        entity.getUtm().getCampaign(), entity.getUtm().getTerm(), entity.getUtm().getContent())
                : null;

        return new ShortUrl(
                entity.getId(),
                entity.getOriginalUrl(),
                entity.getCreatedAt(),
                entity.getUserId(),
                entity.isCustomAlias(),
                entity.getClickCount(),
                entity.getExpiresAt(),
                entity.getTitle(),
                entity.getTags(),
                utm,
                entity.getDeletedAt());
    }

    private UtmParamsEntity toUtmEntity(UtmParams utm) {
        if (utm == null) {
            return null;
        }
        return new UtmParamsEntity(utm.source(), utm.medium(), utm.campaign(), utm.term(), utm.content());
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
