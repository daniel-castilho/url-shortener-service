package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Schema V8: creates the {@code custom_domains} collection used by the branded-domain feature.
 *
 * <ul>
 *   <li>unique index on {@code host} — a host can be claimed only once;
 *   <li>{@code userId} index — owner-scoped listing/delete.
 * </ul>
 *
 * Idempotent — re-running on an already-schema'd database is a no-op.
 */
@Component
public class V8EnsureCustomDomainsCollection implements SchemaMigration {

  @Override
  public int version() {
    return 8;
  }

  @Override
  public String description() {
    return "Create custom_domains collection with unique host index";
  }

  @Override
  public void apply(MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(MongoCollections.CUSTOM_DOMAINS)) {
      mongoTemplate.createCollection(MongoCollections.CUSTOM_DOMAINS);
    }
    IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.CUSTOM_DOMAINS);
    indexOps.ensureIndex(new Index("host", Sort.Direction.ASC).unique());
    indexOps.ensureIndex(new Index("userId", Sort.Direction.ASC));
  }
}
