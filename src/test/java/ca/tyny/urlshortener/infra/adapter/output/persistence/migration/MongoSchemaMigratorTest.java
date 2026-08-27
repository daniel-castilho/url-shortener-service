package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MongoSchemaMigrator unit tests")
class MongoSchemaMigratorTest {

    private static final List<SchemaMigration> ALL_MIGRATIONS = List.of(
            new V1Baseline(), new V2DropOriginalUrlUniqueIndex(), new V3EnsureUserIdIndex(),
            new V4EnsureClickEventsIndexes(), new V5AddExpiresAtTtlIndex());

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final IndexOperations shortUrlsIndexOps = mock(IndexOperations.class);
    private final IndexOperations clickEventsIndexOps = mock(IndexOperations.class);
    private final MetricsPort metrics = mock(MetricsPort.class);

    private void stubFreshDatabase() {
        when(mongoTemplate.collectionExists(MongoSchemaMigrator.HISTORY_COLLECTION)).thenReturn(false);
        when(mongoTemplate.findAll(Document.class, MongoSchemaMigrator.HISTORY_COLLECTION))
                .thenReturn(new ArrayList<>());
        when(mongoTemplate.indexOps("short_urls")).thenReturn(shortUrlsIndexOps);
        when(mongoTemplate.indexOps("click_events")).thenReturn(clickEventsIndexOps);
    }

    @Test
    @DisplayName("Applies all migrations once, in ascending version order, on a fresh database")
    void appliesAllOnFreshDatabase() {
        stubFreshDatabase();
        MongoSchemaMigrator migrator = new MongoSchemaMigrator(mongoTemplate, ALL_MIGRATIONS, metrics);

        migrator.migrate();

        verify(mongoTemplate).createCollection(MongoSchemaMigrator.HISTORY_COLLECTION);
        verify(mongoTemplate).collectionExists("short_urls");
        verify(mongoTemplate).collectionExists("click_events");
        verify(shortUrlsIndexOps).dropIndex("originalUrl_1");
        verify(shortUrlsIndexOps, times(2)).ensureIndex(any());
        verify(clickEventsIndexOps, times(2)).ensureIndex(any());

        org.mockito.ArgumentCaptor<Document> captor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(mongoTemplate, times(5)).save(captor.capture(), eq(MongoSchemaMigrator.HISTORY_COLLECTION));
        List<Integer> recordedVersions = captor.getAllValues().stream()
                .map(doc -> doc.getInteger("version"))
                .toList();
        assertThat(recordedVersions).containsExactly(1, 2, 3, 4, 5);
        Document v5 = captor.getAllValues().get(4);
        assertThat(v5.getString("checksum")).isEqualTo(checksumOf(V5AddExpiresAtTtlIndex.class));
        assertThat(v5.get("appliedAt")).isNotNull();
        verify(metrics, times(5)).recordMigrationApplied();
        verify(metrics, never()).recordMigrationFailed();
    }

    @Test
    @DisplayName("Skips already applied migrations with matching checksums")
    void skipsAlreadyAppliedWithMatchingChecksums() {
        when(mongoTemplate.collectionExists(MongoSchemaMigrator.HISTORY_COLLECTION)).thenReturn(true);
        List<Document> history = new ArrayList<>();
        for (SchemaMigration m : ALL_MIGRATIONS) {
            history.add(historyDoc(m.version(), m.description(), checksumOf(m.getClass())));
        }
        when(mongoTemplate.findAll(Document.class, MongoSchemaMigrator.HISTORY_COLLECTION)).thenReturn(history);
        MongoSchemaMigrator migrator = new MongoSchemaMigrator(mongoTemplate, ALL_MIGRATIONS, metrics);

        migrator.migrate();

        verify(mongoTemplate, never()).createCollection(any(String.class));
        verify(mongoTemplate, never()).save(any(), any());
        verify(metrics, never()).recordMigrationApplied();
    }

    @Test
    @DisplayName("Re-applies an idempotent migration whose recorded checksum drifted")
    void reappliesIdempotentMigrationOnChecksumDrift() {
        when(mongoTemplate.collectionExists(MongoSchemaMigrator.HISTORY_COLLECTION)).thenReturn(true);
        List<Document> history = new ArrayList<>();
        history.add(historyDoc(1, new V1Baseline().description(), "stale-checksum-v1"));
        for (int i = 2; i <= 5; i++) {
            SchemaMigration m = ALL_MIGRATIONS.get(i - 1);
            history.add(historyDoc(m.version(), m.description(), checksumOf(m.getClass())));
        }
        when(mongoTemplate.findAll(Document.class, MongoSchemaMigrator.HISTORY_COLLECTION)).thenReturn(history);
        when(mongoTemplate.indexOps("short_urls")).thenReturn(shortUrlsIndexOps);
        MongoSchemaMigrator migrator = new MongoSchemaMigrator(mongoTemplate, ALL_MIGRATIONS, metrics);

        migrator.migrate();

        verify(mongoTemplate, times(1)).save(any(), eq(MongoSchemaMigrator.HISTORY_COLLECTION));
        verify(mongoTemplate).createCollection("short_urls");
        verify(shortUrlsIndexOps, never()).dropIndex(any());
        verify(metrics).recordMigrationApplied();
    }

    @Test
    @DisplayName("Keeps going when the legacy unique-index drop is a no-op (index never existed)")
    void toleratesMissingOriginalUrlIndex() {
        stubFreshDatabase();
        org.mockito.Mockito.doThrow(new IllegalStateException("index not found"))
                .when(shortUrlsIndexOps).dropIndex("originalUrl_1");
        MongoSchemaMigrator migrator = new MongoSchemaMigrator(mongoTemplate, ALL_MIGRATIONS, metrics);

        migrator.migrate();

        verify(mongoTemplate, times(5)).save(any(), eq(MongoSchemaMigrator.HISTORY_COLLECTION));
    }

    @Test
    @DisplayName("Aborts startup (fail-fast) when a migration throws")
    void failsFastWhenMigrationThrows() {
        stubFreshDatabase();
        when(shortUrlsIndexOps.ensureIndex(any()))
                .thenThrow(new IllegalStateException("cannot build index"));
        MongoSchemaMigrator migrator = new MongoSchemaMigrator(mongoTemplate, ALL_MIGRATIONS, metrics);

        assertThatThrownBy(migrator::migrate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V3");

        verify(mongoTemplate, times(2)).save(any(), eq(MongoSchemaMigrator.HISTORY_COLLECTION));
        verify(metrics, times(2)).recordMigrationApplied();
        verify(metrics).recordMigrationFailed();
    }

    @Test
    @DisplayName("Rejects duplicate migration versions at construction time")
    void rejectsDuplicateVersions() {
        SchemaMigration dupA = new V1Baseline();
        SchemaMigration dupB = new V1Baseline();
        assertThatThrownBy(() -> new MongoSchemaMigrator(mongoTemplate, List.of(dupA, dupB), metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique");
    }

    private static Document historyDoc(int version, String description, String checksum) {
        Document doc = new Document();
        doc.put("_id", version);
        doc.put("version", version);
        doc.put("description", description);
        doc.put("checksum", checksum);
        doc.put("appliedAt", new java.util.Date());
        return doc;
    }

    private static String checksumOf(Class<?> migrationClass) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(migrationClass.getName().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}