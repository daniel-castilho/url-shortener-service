package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.core.exception.AliasAlreadyExistsException;
import ca.tyny.urlshortener.core.exception.ShortCodeCollisionException;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import ca.tyny.urlshortener.infra.adapter.output.persistence.exception.RepositoryException;
import ca.tyny.urlshortener.infra.adapter.output.persistence.mapper.ShortUrlMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MongoDB persistence adapter implementing UrlRepositoryPort.
 *
 * Responsibilities:
 * - Persist and retrieve shortened URLs from MongoDB
 * - Convert between domain model (ShortUrl) and persistence entity
 *   (ShortUrlEntity)
 * - Encapsulate MongoDB-specific exceptions
 *
 * Patterns applied:
 * - Repository Pattern: implements UrlRepositoryPort
 * - Adapter Pattern: adapts MongoTemplate to the port
 * - Circuit Breaker: resilience to database failures
 * - Mapper Pattern: converts domain ↔ entity
 */
@Repository
public class MongoUrlRepository implements UrlRepositoryPort {

    private static final Logger logger = LoggerFactory.getLogger(MongoUrlRepository.class);

    private final MongoTemplate mongoTemplate;
    private final ShortUrlMapper mapper;

    public MongoUrlRepository(MongoTemplate mongoTemplate, ShortUrlMapper mapper) {
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    /**
     * Persists a shortened URL to MongoDB.
     *
     * @param shortUrl the domain short URL to save
     * @throws AliasAlreadyExistsException if a custom alias conflicts with existing _id
     * @throws ShortCodeCollisionException if a generated code collides with existing _id
     * @throws RepositoryException if a database error occurs
     */
    @Override
    @CircuitBreaker(name = "databaseCb")
    public void save(ShortUrl shortUrl) {
        try {
            ShortUrlEntity entity = mapper.toPersistence(shortUrl);
            mongoTemplate.save(entity);
            logger.debug("Short URL saved successfully: {}", shortUrl.id());
        } catch (DuplicateKeyException e) {
            logger.warn("Duplicate _id conflict for code: {}", shortUrl.id());
            if (shortUrl.isCustomAlias()) {
                throw new AliasAlreadyExistsException(shortUrl.id());
            }
            // Collision on auto-generated code — retryable by the caller
            throw new ShortCodeCollisionException(shortUrl.id());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid data when saving short URL", e);
            throw new RepositoryException("Invalid data for persistence", e);
        } catch (Exception e) {
            logger.error("Error saving short URL to MongoDB", e);
            throw new RepositoryException("Failed to persist short URL", e);
        }
    }

    /**
     * Finds a shortened URL by its ID.
     *
     * @param id the unique identifier of the short URL
     * @return Optional containing the URL if found, or empty if not
     * @throws RepositoryException if a database error occurs
     */
    @Override
    @CircuitBreaker(name = "databaseCb")
    public Optional<ShortUrl> findById(String id) {
        try {
            ShortUrlEntity entity = mongoTemplate.findById(id, ShortUrlEntity.class);
            if (entity == null) {
                logger.debug("Short URL not found: {}", id);
                return Optional.empty();
            }
            logger.debug("Short URL retrieved successfully: {}", id);
            return Optional.of(mapper.toDomain(entity));
        } catch (IllegalArgumentException e) {
            logger.error("Invalid ID when searching for short URL", e);
            throw new RepositoryException("Invalid ID for search", e);
        } catch (Exception e) {
            logger.error("Error searching for short URL in MongoDB: {}", id, e);
            throw new RepositoryException("Failed to retrieve short URL", e);
        }
    }

    /**
     * Checks if a shortened URL exists by its identifier.
     *
     * @param id the unique identifier
     * @return true if exists, false otherwise
     * @throws RepositoryException if a database error occurs
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
            logger.error("Error checking existence of short URL in MongoDB: {}", id, e);
            throw new RepositoryException("Failed to check short URL existence", e);
        }
    }

    /**
     * Atomically increments the click counter for a short URL.
     *
     * Uses a server-side $inc so concurrent increments are never lost.
     * A missing code is a no-op (no document is created).
     *
     * @param id the unique identifier of the short URL
     * @throws RepositoryException if a database error occurs
     */
    @Override
    @CircuitBreaker(name = "databaseCb")
    public void incrementClickCount(String id) {
        try {
            org.springframework.data.mongodb.core.query.Update update =
                    new org.springframework.data.mongodb.core.query.Update().inc("clickCount", 1);
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                    update,
                    ShortUrlEntity.class);
            logger.debug("Click count incremented for: {}", id);
        } catch (Exception e) {
            logger.error("Error incrementing click count in MongoDB: {}", id, e);
            throw new RepositoryException("Failed to increment click count", e);
        }
    }
}
