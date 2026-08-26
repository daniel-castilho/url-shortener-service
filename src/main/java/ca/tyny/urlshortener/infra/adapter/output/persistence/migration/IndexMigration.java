package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class IndexMigration {

    private static final Logger log = LoggerFactory.getLogger(IndexMigration.class);

    private final MongoTemplate mongoTemplate;

    public IndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void migrateIndexes() {
        dropOriginalUrlUniqueIndex();
        ensureUserIdIndex();
        log.info("Index migration complete: _id (unique), originalUrl unique index dropped, userId index ensured");
    }

    private void dropOriginalUrlUniqueIndex() {
        try {
            IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.SHORT_URLS);
            indexOps.dropIndex("originalUrl_1");
            log.info("Dropped unique index on originalUrl (no URL dedup)");
        } catch (Exception e) {
            log.debug("Index originalUrl_1 not found or already dropped: {}", e.getMessage());
        }
    }

    private void ensureUserIdIndex() {
        try {
            IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.SHORT_URLS);
            indexOps.ensureIndex(new Index("userId", Sort.Direction.ASC));
            log.info("Ensured userId index exists on short_urls collection");
        } catch (Exception e) {
            log.warn("Failed to ensure userId index: {}", e.getMessage());
        }
    }
}
