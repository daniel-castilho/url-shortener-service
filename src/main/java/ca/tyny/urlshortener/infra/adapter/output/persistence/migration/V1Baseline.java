package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Baseline migration: creates the core collections if they do not exist yet.
 * Idempotent — re-running on an already-schema'd database is a no-op.
 */
@Component
public class V1Baseline implements SchemaMigration {

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "Baseline: create core collections";
    }

    @Override
    public void apply(MongoTemplate mongoTemplate) {
        ensureCollection(mongoTemplate, MongoCollections.SHORT_URLS);
        ensureCollection(mongoTemplate, MongoCollections.CLICK_EVENTS);
    }

    private static void ensureCollection(MongoTemplate mongoTemplate, String collection) {
        if (!mongoTemplate.collectionExists(collection)) {
            mongoTemplate.createCollection(collection);
        }
    }
}