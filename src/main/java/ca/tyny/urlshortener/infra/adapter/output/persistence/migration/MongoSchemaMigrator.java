package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Versioned, in-code schema migration runner.
 * <p>
 * Applies {@link SchemaMigration}s once, in ascending version order, on application
 * startup, and records each application in the {@code schema_migrations} history
 * collection (one document per version). The runner is fail-fast: if a migration throws,
 * startup aborts and the failed migration is never recorded as applied.
 * <p>
 * Each migration must be idempotent so that a fresh database (or a wiped history) is
 * migrated to the current schema with a single pass. If the recorded checksum of an
 * already-applied version no longer matches the current class, an idempotent migration
 * is re-applied and its record updated; a non-idempotent one aborts startup.
 */
@Component
public class MongoSchemaMigrator {

    /** Collection name for the migration history (version, description, checksum, appliedAt). */
    public static final String HISTORY_COLLECTION = "schema_migrations";

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaMigrator.class);

    private final MongoTemplate mongoTemplate;
    private final List<SchemaMigration> migrations;
    private final MetricsPort metrics;

    public MongoSchemaMigrator(MongoTemplate mongoTemplate, List<SchemaMigration> migrations, MetricsPort metrics) {
        this.mongoTemplate = mongoTemplate;
        this.migrations = migrations.stream()
                .sorted(Comparator.comparingInt(SchemaMigration::version))
                .toList();
        this.metrics = metrics;
        validateUniqueVersions();
        validateVersionNumbers();
    }

    @PostConstruct
    public void migrate() {
        ensureHistoryCollection();
        Map<Integer, Document> applied = loadAppliedMigrationDocs();

        for (SchemaMigration migration : migrations) {
            int version = migration.version();
            Document existing = applied.get(version);
            String checksum = checksum(migration);

            if (existing != null && checksumMatches(existing, checksum)) {
                log.debug("Schema migration V{} ({}) already applied", version, migration.description());
                continue;
            }
            if (existing != null && !migration.idempotent()) {
                throw new IllegalStateException("Schema migration V" + version + " (" + migration.description()
                        + ") has a different checksum than the recorded one but is not idempotent: "
                        + "refusing to re-apply");
            }
            if (existing != null) {
                log.warn("Re-applying idempotent schema migration V{} ({}) after checksum change",
                        version, migration.description());
            }

            try {
                migration.apply(mongoTemplate);
            } catch (Exception e) {
                metrics.recordMigrationFailed();
                log.error("Schema migration V{} ({}) failed; aborting startup",
                        version, migration.description(), e);
                throw new IllegalStateException("Schema migration V" + version + " (" + migration.description()
                        + ") failed", e);
            }

            record(migration, checksum);
            metrics.recordMigrationApplied();
            log.info("Applied schema migration V{} — {}", version, migration.description());
        }
    }

    private void validateUniqueVersions() {
        long distinct = migrations.stream().map(SchemaMigration::version).distinct().count();
        if (distinct != migrations.size()) {
            throw new IllegalStateException("Schema migrations must have unique version numbers");
        }
    }

    private void validateVersionNumbers() {
        for (SchemaMigration migration : migrations) {
            if (migration.version() <= 0) {
                throw new IllegalStateException("Schema migration version must be positive; got "
                        + migration.version() + " for " + migration.description());
            }
        }
    }

    private void ensureHistoryCollection() {
        if (!mongoTemplate.collectionExists(HISTORY_COLLECTION)) {
            mongoTemplate.createCollection(HISTORY_COLLECTION);
        }
    }

    private Map<Integer, Document> loadAppliedMigrationDocs() {
        return mongoTemplate.findAll(Document.class, HISTORY_COLLECTION).stream()
                .filter(doc -> doc.getInteger("version") != null)
                .collect(Collectors.toMap(doc -> doc.getInteger("version"), doc -> doc));
    }

    private static boolean checksumMatches(Document existing, String checksum) {
        return checksum.equals(existing.getString("checksum"));
    }

    private void record(SchemaMigration migration, String checksum) {
        Document doc = new Document();
        doc.put("_id", migration.version());
        doc.put("version", migration.version());
        doc.put("description", migration.description());
        doc.put("checksum", checksum);
        doc.put("appliedAt", Instant.now());
        mongoTemplate.save(doc, HISTORY_COLLECTION);
    }

    private static String checksum(SchemaMigration migration) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(migration.getClass().getName().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}