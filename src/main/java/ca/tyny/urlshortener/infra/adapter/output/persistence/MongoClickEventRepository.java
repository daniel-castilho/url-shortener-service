package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickEventDocument;
import ca.tyny.urlshortener.infra.adapter.output.persistence.exception.RepositoryException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MongoDB persistence adapter for click events.
 *
 * Bulk-inserts analytics events; no reads are exposed here yet (aggregation
 * queries are a future concern). Failures surface as RepositoryException so
 * the worker can apply its bounded retry policy.
 */
@Repository
public class MongoClickEventRepository {

    private static final Logger logger = LoggerFactory.getLogger(MongoClickEventRepository.class);

    private final MongoTemplate mongoTemplate;

    public MongoClickEventRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Inserts a batch of click events in one round trip.
     *
     * @param events the batch to persist (must not be null)
     * @throws RepositoryException if a database error occurs
     */
    @CircuitBreaker(name = "databaseCb")
    public void insertAll(List<ClickEventDocument> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try {
            mongoTemplate.insert(events, ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections.CLICK_EVENTS);
            logger.debug("Persisted {} click events", events.size());
        } catch (Exception e) {
            logger.error("Error bulk-inserting click events into MongoDB", e);
            throw new RepositoryException("Failed to persist click events", e);
        }
    }
}
