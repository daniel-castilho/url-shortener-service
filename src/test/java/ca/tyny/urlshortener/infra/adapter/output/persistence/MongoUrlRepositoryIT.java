package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MongoDB URL Repository Integration Tests")
class MongoUrlRepositoryIT extends BaseIntegrationTest {

    @Autowired
    private MongoUrlRepository repository;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    private static final String TEST_ID = "test123";
    private static final String TEST_URL = "https://www.example.com/test";
    private static final String USER_ID = "user123";

    @BeforeEach
    void setUp() {
        // No explicit cleanup needed; each test runs against a fresh container.
    }

    @Test
    @DisplayName("Should save and retrieve ShortUrl")
    void shouldSaveAndRetrieve() {
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
        repository.save(shortUrl);
        Optional<ShortUrl> retrieved = repository.findById(TEST_ID);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().id()).isEqualTo(TEST_ID);
        assertThat(retrieved.get().originalUrl()).isEqualTo(TEST_URL);
        assertThat(retrieved.get().createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Should return empty Optional for non-existent ID")
    void shouldReturnEmptyForNonExistentId() {
        Optional<ShortUrl> result = repository.findById("nonexistent999");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should persist multiple URLs")
    void shouldPersistMultipleUrls() {
        ShortUrl url1 = new ShortUrl("id1", "https://example1.com", LocalDateTime.now());
        ShortUrl url2 = new ShortUrl("id2", "https://example2.com", LocalDateTime.now());
        ShortUrl url3 = new ShortUrl("id3", "https://example3.com", LocalDateTime.now());
        repository.save(url1);
        repository.save(url2);
        repository.save(url3);
        assertThat(repository.findById("id1")).isPresent();
        assertThat(repository.findById("id2")).isPresent();
        assertThat(repository.findById("id3")).isPresent();
    }

    @Test
    @DisplayName("Should handle URLs with special characters")
    void shouldHandleSpecialCharacters() {
        String special = "https://example.com/path?param=value&other=123#anchor";
        ShortUrl shortUrl = new ShortUrl("special123", special, LocalDateTime.now());
        repository.save(shortUrl);
        Optional<ShortUrl> retrieved = repository.findById("special123");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().originalUrl()).isEqualTo(special);
    }

    @Test
    @DisplayName("Should increment clickCount atomically")
    void shouldIncrementClickCount() {
        repository.save(new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now()));

        repository.incrementClickCount(TEST_ID);
        repository.incrementClickCount(TEST_ID);

        Optional<ShortUrl> retrieved = repository.findById(TEST_ID);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().clickCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should not lose increments under concurrency")
    void shouldNotLoseConcurrentIncrements() throws Exception {
        repository.save(new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now()));

        int threads = 10;
        int incrementsPerThread = 10;
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < incrementsPerThread; i++) {
                        repository.incrementClickCount(TEST_ID);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Optional<ShortUrl> retrieved = repository.findById(TEST_ID);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().clickCount())
                .isEqualTo((long) threads * incrementsPerThread);
    }

    @Test
    @DisplayName("Should be a no-op when incrementing a non-existent code")
    void shouldBeNoOpForMissingCode() {
        repository.incrementClickCount("missing999");

        assertThat(repository.findById("missing999")).isEmpty();
    }

    // ========== LinkQueryPort: findByUserId with cursor pagination ==========

    @Test
    @DisplayName("findByUserId - returns only own links, newest first")
    void findByUserId_returnsOnlyOwnLinks() {
        Instant base = Instant.now();
        ShortUrl u1 = new ShortUrl("a1", "https://a1.com", LocalDateTime.ofInstant(base.minusSeconds(2), java.time.ZoneOffset.UTC), USER_ID);
        ShortUrl u2 = new ShortUrl("a2", "https://a2.com", LocalDateTime.ofInstant(base.minusSeconds(1), java.time.ZoneOffset.UTC), USER_ID);
        ShortUrl u3 = new ShortUrl("b1", "https://b1.com", LocalDateTime.ofInstant(base, java.time.ZoneOffset.UTC), "other-user");
        repository.save(u1);
        repository.save(u2);
        repository.save(u3);

        PageResult<ShortUrl> page = repository.findByUserId(USER_ID, 10, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo("a2"); // newest first
        assertThat(page.items().get(1).id()).isEqualTo("a1");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("findByUserId - cursor pagination advances correctly")
    void findByUserId_cursorPagination() {
        // Use UTC-based timestamps to match cursor encoding/decoding
        Instant base = Instant.now();
        ShortUrl u1 = new ShortUrl("a1", "https://a1.com", LocalDateTime.ofInstant(base.minusSeconds(2), java.time.ZoneOffset.UTC), USER_ID);
        ShortUrl u2 = new ShortUrl("a2", "https://a2.com", LocalDateTime.ofInstant(base.minusSeconds(1), java.time.ZoneOffset.UTC), USER_ID);
        ShortUrl u3 = new ShortUrl("a3", "https://a3.com", LocalDateTime.ofInstant(base, java.time.ZoneOffset.UTC), USER_ID);
        repository.save(u1);
        repository.save(u2);
        repository.save(u3);

        PageResult<ShortUrl> page1 = repository.findByUserId(USER_ID, 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.items().get(0).id()).isEqualTo("a3");
        assertThat(page1.items().get(1).id()).isEqualTo("a2");
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotNull();

        PageResult<ShortUrl> page2 = repository.findByUserId(USER_ID, 2, page1.nextCursor());
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).id()).isEqualTo("a1");
        assertThat(page2.hasMore()).isFalse();
    }

    @Test
    @DisplayName("findByUserId - limit capped at MAX_LIMIT (100)")
    void findByUserId_limitCapped() {
        for (int i = 0; i < 150; i++) {
            repository.save(new ShortUrl("id" + i, "https://" + i + ".com", LocalDateTime.now(), USER_ID));
        }

        // Repository should cap limit at 100 (defensive, matches PageRequest.MAX_LIMIT)
        PageResult<ShortUrl> page = repository.findByUserId(USER_ID, 1000, null);

        assertThat(page.items()).hasSize(100);
    }

    @Test
    @DisplayName("findByUserId - malformed cursor throws IllegalArgumentException")
    void findByUserId_malformedCursorThrows() {
        assertThatThrownBy(() -> repository.findByUserId(USER_ID, 10, new Cursor("not-valid-base64!")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== LinkMutationPort: update ==========

    @Test
    @DisplayName("update - keeps _id, persists new fields")
    void update_keepsIdPersistsFields() {
        ShortUrl original = new ShortUrl("keep123", "https://original.com", LocalDateTime.now(), USER_ID);
        repository.save(original);

        ShortUrl updated = new ShortUrl("keep123", "https://updated.com", LocalDateTime.now(), USER_ID)
                .withTitle("New Title")
                .withTags(List.of("tag1", "tag2"))
                .withExpiresAt(Instant.now().plusSeconds(3600));
        repository.update(updated);

        Optional<ShortUrl> retrieved = repository.findById("keep123");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().originalUrl()).isEqualTo("https://updated.com");
        assertThat(retrieved.get().title()).isEqualTo("New Title");
        assertThat(retrieved.get().tags()).containsExactly("tag1", "tag2");
        assertThat(retrieved.get().expiresAt()).isNotNull();
    }

    // ========== LinkMutationPort: archive ==========

    @Test
    @DisplayName("archive - sets deletedAt; repeated call updates again (idempotency is at use case)")
    void archive_setsDeletedAt() {
        ShortUrl shortUrl = new ShortUrl("arch123", "https://archive.com", LocalDateTime.now(), USER_ID);
        repository.save(shortUrl);

        repository.archive("arch123");

        Optional<ShortUrl> retrieved = repository.findById("arch123");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().deletedAt()).isNotNull();

        // Repo does not enforce idempotency; use case does. Second call updates timestamp.
        repository.archive("arch123");
        Optional<ShortUrl> retrieved2 = repository.findById("arch123");
        assertThat(retrieved2.get().deletedAt()).isNotNull();
    }
}
