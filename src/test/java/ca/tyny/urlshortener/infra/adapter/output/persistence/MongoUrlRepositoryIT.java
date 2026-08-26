package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MongoDB URL Repository Integration Tests")
class MongoUrlRepositoryIT extends BaseIntegrationTest {

    @Autowired
    private MongoUrlRepository repository;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    private static final String TEST_ID = "test123";
    private static final String TEST_URL = "https://www.example.com/test";

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
}
