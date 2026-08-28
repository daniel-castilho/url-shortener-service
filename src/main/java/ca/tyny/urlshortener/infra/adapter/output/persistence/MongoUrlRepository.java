package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.core.exception.AliasAlreadyExistsException;
import ca.tyny.urlshortener.core.exception.ShortCodeCollisionException;
import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import ca.tyny.urlshortener.infra.adapter.output.persistence.exception.RepositoryException;
import ca.tyny.urlshortener.infra.adapter.output.persistence.mapper.ShortUrlMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB persistence adapter implementing all URL-related ports.
 *
 * Responsibilities:
 * - Persist and retrieve shortened URLs from MongoDB
 * - Convert between domain model (ShortUrl) and persistence entity
 *   (ShortUrlEntity)
 * - Encapsulate MongoDB-specific exceptions
 *
 * Implements three ports:
 * - {@link UrlRepositoryPort}: used by the shortener/redirect write path
 * - {@link LinkQueryPort}: query operations for the links-as-resource API
 * - {@link LinkMutationPort}: mutation operations for the links-as-resource API
 *
 * Patterns applied:
 * - Repository Pattern: implements UrlRepositoryPort, LinkQueryPort, LinkMutationPort
 * - Adapter Pattern: adapts MongoTemplate to the ports
 * - Circuit Breaker: resilience to database failures
 * - Mapper Pattern: converts domain ↔ entity
 */
@Repository
public class MongoUrlRepository implements UrlRepositoryPort, LinkQueryPort, LinkMutationPort {

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
    public void incrementClickCount(String id, long delta) {
        try {
            org.springframework.data.mongodb.core.query.Update update =
                    new org.springframework.data.mongodb.core.query.Update().inc("clickCount", delta);
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                    update,
                    ShortUrlEntity.class);
            logger.debug("Click count incremented by {} for: {}", delta, id);
        } catch (Exception e) {
            logger.error("Error incrementing click count in MongoDB: {}", id, e);
            throw new RepositoryException("Failed to increment click count", e);
        }
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public PageResult<ShortUrl> findByUserId(String userId, int limit, Cursor cursor) {
        try {
            // Cap limit at 100 (PageRequest.MAX_LIMIT) for defense in depth
            if (limit > 100) {
                limit = 100;
            }

            Query query = Query.query(Criteria.where("userId").is(userId))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"));

            // Apply cursor if present
            if (cursor != null) {
                long createdAtMillis = cursor.createdAtEpochMillis();
                String cursorId = cursor.id();
                // Convert epoch millis to LocalDateTime in UTC for comparison with stored LocalDateTime
                java.time.LocalDateTime cursorCreatedAt = java.time.Instant.ofEpochMilli(createdAtMillis)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDateTime();
                query.addCriteria(new Criteria().orOperator(
                        Criteria.where("createdAt").lt(cursorCreatedAt),
                        new Criteria().andOperator(
                                Criteria.where("createdAt").is(cursorCreatedAt),
                                Criteria.where("_id").lt(cursorId)
                        )
                ));
            }

            query.limit(limit + 1); // fetch one extra to detect hasMore

            List<ShortUrlEntity> entities = mongoTemplate.find(query, ShortUrlEntity.class);
            boolean hasMore = entities.size() > limit;
            if (hasMore) {
                entities = entities.subList(0, limit);
            }

            List<ShortUrl> items = entities.stream()
                    .map(mapper::toDomain)
                    .toList();

            Cursor nextCursor = hasMore && !items.isEmpty()
                    ? Cursor.of(items.getLast().createdAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), items.getLast().id())
                    : null;

            return PageResult.of(items, nextCursor);
        } catch (IllegalArgumentException e) {
            // Malformed cursor - let it propagate for 400 handling
            throw e;
        } catch (Exception e) {
            logger.error("Error finding links by userId: {}", userId, e);
            throw new RepositoryException("Failed to find links by userId", e);
        }
    }

    // ========== LinkMutationPort implementation ==========

    @Override
    @CircuitBreaker(name = "databaseCb")
    public void update(ShortUrl shortUrl) {
        try {
            ShortUrlEntity entity = mapper.toPersistence(shortUrl);
            mongoTemplate.save(entity);
            logger.debug("Short URL updated successfully: {}", shortUrl.id());
        } catch (Exception e) {
            logger.error("Error updating short URL: {}", shortUrl.id(), e);
            throw new RepositoryException("Failed to update short URL", e);
        }
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public void archive(String id) {
        try {
            Update update = new Update().set("deletedAt", java.time.Instant.now());
            Query query = Query.query(Criteria.where("_id").is(id));
            mongoTemplate.updateFirst(query, update, ShortUrlEntity.class);
            logger.debug("Short URL archived: {}", id);
        } catch (Exception e) {
            logger.error("Error archiving short URL: {}", id, e);
            throw new RepositoryException("Failed to archive short URL", e);
        }
    }
}
