package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * A single versioned, ordered schema migration.
 * <p>
 * Migrations are applied once by {@link MongoSchemaMigrator} on startup, in ascending
 * {@link #version()} order, and recorded in the {@code schema_migrations} history
 * collection. Each migration must be idempotent so it can be safely re-applied when the
 * history is missing (e.g. on a freshly cloned database) or when its checksum drifts.
 */
public interface SchemaMigration {

    /** Ascending, non-zero version number (must be unique across enabled migrations). */
    int version();

    /** Short human-readable description used in the history log. */
    String description();

    /** Applies this migration. Must be idempotent and tolerant of already-applied state. */
    void apply(MongoTemplate mongoTemplate);

    /**
     * Whether re-applying this migration is safe. Idempotent migrations are re-applied
     * (instead of failing) when the recorded checksum no longer matches the current class.
     */
    default boolean idempotent() {
        return true;
    }
}