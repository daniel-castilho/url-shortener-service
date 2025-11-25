package com.example.urlshortener.infra.adapter.output.persistence;

import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import com.example.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import com.example.urlshortener.infra.adapter.output.persistence.exception.RepositoryException;
import com.example.urlshortener.infra.adapter.output.persistence.mapper.ShortUrlMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Implementação da porta de persistência para MongoDB.
 *
 * Responsabilidades:
 * - Persistir e recuperar URLs encurtadas do MongoDB
 * - Converter entre domain model (ShortUrl) e persistence entity
 * (ShortUrlEntity)
 * - Encapsular exceções específicas do MongoDB
 *
 * Aplicação de padrões:
 * - Repository Pattern: implementa UrlRepositoryPort
 * - Adapter Pattern: adapta MongoTemplate para a porta
 * - Circuit Breaker: resiliência a falhas do banco de dados
 * - Mapper Pattern: converte domain ↔ entity
 */
@Repository
public class MongoUrlRepository implements UrlRepositoryPort {

    private static final Logger logger = LoggerFactory.getLogger(MongoUrlRepository.class);

    private final MongoTemplate mongoTemplate;
    private final ShortUrlMapper mapper;

    /**
     * Construtor com injeção de dependências.
     *
     * @param mongoTemplate template do Spring Data MongoDB para operações
     * @param mapper        mapper para conversão domain ↔ entity
     */
    public MongoUrlRepository(MongoTemplate mongoTemplate, ShortUrlMapper mapper) {
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    /**
     * Persiste uma URL encurtada no MongoDB.
     *
     * @param shortUrl a URL encurtada do domínio a ser salva
     * @throws RepositoryException se ocorrer erro ao persistir no MongoDB
     */
    @Override
    @CircuitBreaker(name = "databaseCb")
    public void save(ShortUrl shortUrl) {
        try {
            ShortUrlEntity entity = mapper.toPersistence(shortUrl);
            mongoTemplate.save(entity);
            logger.debug("URL encurtada salva com sucesso: {}", shortUrl.id());
        } catch (IllegalArgumentException e) {
            logger.error("Dados inválidos ao salvar URL encurtada", e);
            throw new RepositoryException("Dados inválidos para persistência", e);
        } catch (Exception e) {
            logger.error("Erro ao salvar URL encurtada no MongoDB", e);
            throw new RepositoryException("Falha ao persistir URL encurtada", e);
        }
    }

    /**
     * Busca uma URL encurtada pelo seu ID.
     *
     * @param id o identificador único da URL encurtada
     * @return Optional contendo a URL se encontrada, ou vazio se não existir
     * @throws RepositoryException se ocorrer erro ao consultar o MongoDB
     */
    @Override
    @CircuitBreaker(name = "databaseCb")
    public Optional<ShortUrl> findById(String id) {
        try {
            ShortUrlEntity entity = mongoTemplate.findById(id, ShortUrlEntity.class);
            if (entity == null) {
                logger.debug("URL encurtada não encontrada: {}", id);
                return Optional.empty();
            }
            logger.debug("URL encurtada recuperada com sucesso: {}", id);
            return Optional.of(mapper.toDomain(entity));
        } catch (IllegalArgumentException e) {
            logger.error("ID inválido ao buscar URL encurtada", e);
            throw new RepositoryException("ID inválido para busca", e);
        } catch (Exception e) {
            logger.error("Erro ao buscar URL encurtada no MongoDB: {}", id, e);
            throw new RepositoryException("Falha ao recuperar URL encurtada", e);
        }

    }

    /**
     * Verifica se uma URL encurtada existe por seu identificador.
     *
     * @param id o identificador único
     * @return true se existir, false caso contrário
     * @throws RepositoryException se ocorrer erro ao consultar o MongoDB
     */
    @Override
    @CircuitBreaker(name = "databaseCb")
    public boolean existsById(String id) {
        try {
            return mongoTemplate.exists(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                    ShortUrlEntity.class);
        } catch (Exception e) {
            logger.error("Erro ao verificar existência de URL encurtada no MongoDB: {}", id, e);
            throw new RepositoryException("Falha ao verificar existência de URL encurtada", e);
        }
    }
}
