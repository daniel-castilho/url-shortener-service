package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Ensures indexes on the {@code users} collection for user link listing:
 * <ul>
 *   <li>{@code userId} (non-unique, already exists from V3)</li>
 *   <li>{@code (userId, createdAt)} compound non-unique index for cursor pagination</li>
 * </ul>
 * <p>
 * The compound index supports the cursor-based pagination query which orders by
 * {@code createdAt DESC, _id DESC}.
 */
@Component
public class V7EnsureUserLinksIndex implements SchemaMigration {

    @Override
    public int version() {
        return 7;
    }

    @Override
    public String description() {
        return "Ensure (userId, createdAt) compound index on short_urls for user link listing";
    }

    @Override
    public void apply(MongoTemplate mongoTemplate) {
        IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.SHORT_URLS);
        // Compound index for cursor pagination: userId ASC, createdAt DESC
        indexOps.ensureIndex(new Index()
                .on("userId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC));
    }
}