package ca.tyny.urlshortener.infra.adapter.output.persistence.migration;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Schema migration versioned runner integration tests")
class SchemaMigrationIT extends BaseIntegrationTest {

    private static final String SHORT_URLS = "short_urls";
    private static final String CLICK_EVENTS = "click_events";
    private static final String USERS = "users";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoSchemaMigrator migrator;

    @Test
    @DisplayName("Applying migrations on a fresh database creates history and all desired indexes")
    void appliesAllMigrationsOnFreshDatabase() {
        mongoTemplate.getDb().drop();
        migrator.migrate();

        List<Document> history = mongoTemplate.findAll(Document.class, MongoSchemaMigrator.HISTORY_COLLECTION);
        assertThat(history).hasSize(7);
        assertThat(history.stream().map(doc -> doc.getInteger("version"))).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(history).allSatisfy(doc -> {
            assertThat(doc.getString("checksum")).isNotBlank();
            assertThat(doc.getString("description")).isNotBlank();
            assertThat(doc.get("appliedAt")).isNotNull();
        });

        List<IndexInfo> shortUrlsIndexes = mongoTemplate.indexOps(SHORT_URLS).getIndexInfo();
        assertThat(indexNames(shortUrlsIndexes))
                .contains("_id_", "userId_1", "expiresAt_1")
                .doesNotContain("originalUrl_1");
        assertThat(indexNames(shortUrlsIndexes)).contains("userId_1_createdAt_-1"); // V7 compound index
        IndexInfo ttlIndex = shortUrlsIndexes.stream()
                .filter(index -> "expiresAt_1".equals(index.getName()))
                .findFirst().orElseThrow();
        assertThat(ttlIndex.getExpireAfter()).contains(Duration.ZERO);

        List<String> clickEventsIndexes = indexNames(mongoTemplate.indexOps(CLICK_EVENTS).getIndexInfo());
        assertThat(clickEventsIndexes).contains("_id_", "shortCode_1_timestamp_1", "timestamp_1");

        List<String> usersIndexes = indexNames(mongoTemplate.indexOps(USERS).getIndexInfo());
        assertThat(usersIndexes).contains("_id_", "email_1", "plan_1", "createdAt_1");
        IndexInfo emailUniqueIndex = mongoTemplate.indexOps(USERS).getIndexInfo().stream()
                .filter(index -> "email_1".equals(index.getName()))
                .findFirst().orElseThrow();
        assertThat(emailUniqueIndex.isUnique()).isTrue();
    }

    @Test
    @DisplayName("Re-running migrations is idempotent and never duplicates history")
    void repeatedApplicationIsIdempotent() {
        mongoTemplate.getDb().drop();
        migrator.migrate();
        migrator.migrate();

        List<Document> history = mongoTemplate.findAll(Document.class, MongoSchemaMigrator.HISTORY_COLLECTION);
        assertThat(history).hasSize(7);
        assertThat(indexNames(mongoTemplate.indexOps(SHORT_URLS).getIndexInfo()))
                .contains("userId_1", "expiresAt_1");
        assertThat(indexNames(mongoTemplate.indexOps(CLICK_EVENTS).getIndexInfo()))
                .contains("shortCode_1_timestamp_1", "timestamp_1");
    }

    private static List<String> indexNames(List<IndexInfo> indexInfo) {
        return indexInfo.stream().map(IndexInfo::getName).collect(Collectors.toList());
    }
}