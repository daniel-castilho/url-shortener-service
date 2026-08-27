package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Ensures the {@code userId} index on {@code short_urls} (used for per-user listing and
 * quota aggregation). Uses {@code ensureIndex}, which is idempotent.
 */
@Component
public class V3EnsureUserIdIndex implements SchemaMigration {

    @Override
    public int version() {
        return 3;
    }

    @Override
    public String description() {
        return "Ensure userId index on short_urls";
    }

    @Override
    public void apply(MongoTemplate mongoTemplate) {
        IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.SHORT_URLS);
        indexOps.ensureIndex(new Index("userId", Sort.Direction.ASC));
    }
}