package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Schema V9: creates the {@code click_daily} rollup collection used for cheap temporal click
 * analytics.
 *
 * <p>A unique compound {@code (shortCode, day)} index guarantees one row per (short code, UTC
 * calendar day) — the rollup job upserts on this key, so re-runs overwrite instead of duplicating.
 *
 * <p>Idempotent — re-running on an already-schema'd database is a no-op.
 */
@Component
public class V9EnsureClickDailyCollection implements SchemaMigration {

  @Override
  public int version() {
    return 9;
  }

  @Override
  public String description() {
    return "Create click_daily rollup collection with (shortCode, day) unique index";
  }

  @Override
  public void apply(MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(MongoCollections.CLICK_DAILY)) {
      mongoTemplate.createCollection(MongoCollections.CLICK_DAILY);
    }
    IndexOperations indexOps = mongoTemplate.indexOps(MongoCollections.CLICK_DAILY);
    indexOps.ensureIndex(
        new Index("shortCode", Sort.Direction.ASC).on("day", Sort.Direction.ASC).unique());
  }
}
