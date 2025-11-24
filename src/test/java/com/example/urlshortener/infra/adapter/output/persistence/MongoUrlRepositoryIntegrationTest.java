package com.example.urlshortener.infra.adapter.output.persistence;

import com.example.urlshortener.config.BaseIntegrationTest;
import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MongoDB Integration Tests")
class MongoUrlRepositoryIntegrationTest extends BaseIntegrationTest {

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
}
