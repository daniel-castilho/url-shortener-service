package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Ensures indexes on the {@code users} collection:
 * <ul>
 *   <li>{@code email} — unique index (registration guard, storage-level uniqueness)</li>
 *   <li>{@code plan} — non-unique index</li>
 *   <li>{@code createdAt} — non-unique index</li>
 * </ul>
 * Mirrors the {@code @Indexed} annotations on {@link ca.tyny.urlshortener.infra.adapter.output.persistence.UserEntity}.
 * Idempotent via {@code ensureIndex}.
 */
@Component
public class V6EnsureUserIndexes implements SchemaMigration {

    @Override
    public int version() {
        return 6;
    }

    @Override
    public String description() {
        return "Ensure users collection indexes (email unique, plan, createdAt)";
    }

    @Override
    public void apply(MongoTemplate mongoTemplate) {
        IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.USERS);

        // email — unique (storage-level uniqueness for registration)
        indexOps.ensureIndex(new Index("email", Sort.Direction.ASC).unique());

        // plan — non-unique
        indexOps.ensureIndex(new Index("plan", Sort.Direction.ASC));

        // createdAt — non-unique
        indexOps.ensureIndex(new Index("createdAt", Sort.Direction.ASC));
    }
}