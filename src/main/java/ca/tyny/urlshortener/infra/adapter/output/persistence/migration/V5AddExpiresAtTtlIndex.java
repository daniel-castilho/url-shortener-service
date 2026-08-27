package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Adds a TTL index on {@code expiresAt} for {@code short_urls}. MongoDB's TTL monitor
 * lazily deletes documents once {@code expiresAt} has passed — the application-level
 * expiry check remains the source of truth. Idempotent via {@code ensureIndex}.
 */
@Component
public class V5AddExpiresAtTtlIndex implements SchemaMigration {

    @Override
    public int version() {
        return 5;
    }

    @Override
    public String description() {
        return "Add expiresAt TTL index on short_urls";
    }

    @Override
    public void apply(MongoTemplate mongoTemplate) {
        IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.SHORT_URLS);
        indexOps.ensureIndex(new Index("expiresAt", Sort.Direction.ASC)
                .expire(0, TimeUnit.SECONDS));
    }
}