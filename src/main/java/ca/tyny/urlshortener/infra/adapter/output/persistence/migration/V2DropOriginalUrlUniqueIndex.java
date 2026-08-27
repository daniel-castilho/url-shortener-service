package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Legacy index cleanup: drops the unique index on {@code originalUrl} (no URL
 * deduplication — the same long URL may be shortened to distinct codes). The index does
 * not exist on fresh databases, so dropping is best-effort and must never fail.
 */
@Component
public class V2DropOriginalUrlUniqueIndex implements SchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(V2DropOriginalUrlUniqueIndex.class);

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "Drop unique index on originalUrl (no URL dedup)";
    }

    @Override
    public void apply(MongoTemplate mongoTemplate) {
        try {
            IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.SHORT_URLS);
            indexOps.dropIndex("originalUrl_1");
            log.info("Dropped unique index on originalUrl (no URL dedup)");
        } catch (Exception e) {
            log.debug("Index originalUrl_1 not found or already dropped: {}", e.getMessage());
        }
    }
}