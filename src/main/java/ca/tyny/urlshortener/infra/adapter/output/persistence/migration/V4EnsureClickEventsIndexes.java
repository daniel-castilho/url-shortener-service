package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Ensures the {@code click_events} indexes used by analytics aggregates and retention
 * purge: a compound {@code (shortCode, timestamp)} index and a standalone
 * {@code timestamp} index. Idempotent via {@code ensureIndex}.
 */
@Component
public class V4EnsureClickEventsIndexes implements SchemaMigration {

    @Override
    public int version() {
        return 4;
    }

    @Override
    public String description() {
        return "Ensure click_events analytics indexes";
    }

    @Override
    public void apply(MongoTemplate mongoTemplate) {
        IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.CLICK_EVENTS);
        indexOps.ensureIndex(new Index("shortCode", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.ASC));
        indexOps.ensureIndex(new Index("timestamp", Sort.Direction.ASC));
    }
}