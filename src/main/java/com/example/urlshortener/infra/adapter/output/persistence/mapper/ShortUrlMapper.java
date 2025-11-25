package com.example.urlshortener.infra.adapter.output.persistence.mapper;

import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import org.springframework.stereotype.Component;

/**
 * Mapper responsável pela conversão entre domain model e persistence entity.
 * Segue o padrão de separação entre camadas de domínio e infraestrutura.
 *
 * @author Migration from Cassandra to MongoDB
 */
@Component
public class ShortUrlMapper {

    /**
     * Converte um objeto de domínio (ShortUrl) para entidade de persistência
     * (ShortUrlEntity).
     *
     * @param domain o objeto de domínio contendo os dados da URL encurtada
     * @return a entidade preparada para persistência no MongoDB
     * @throws IllegalArgumentException se o domínio for nulo
     */
    public ShortUrlEntity toPersistence(ShortUrl domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain object cannot be null");
        }

        return new ShortUrlEntity(
                domain.id(),
                domain.originalUrl(),
                domain.createdAt(),
                domain.userId(),
                domain.isCustomAlias());
    }

    /**
     * Converte uma entidade de persistência (ShortUrlEntity) para objeto de domínio
     * (ShortUrl).
     *
     * @param entity a entidade recuperada do MongoDB
     * @return o objeto de domínio com os dados da entidade
     * @throws IllegalArgumentException se a entidade for nula
     */
    public ShortUrl toDomain(ShortUrlEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity object cannot be null");
        }

        return new ShortUrl(
                entity.getId(),
                entity.getOriginalUrl(),
                entity.getCreatedAt(),
                entity.getUserId(),
                entity.isCustomAlias());
    }
}
