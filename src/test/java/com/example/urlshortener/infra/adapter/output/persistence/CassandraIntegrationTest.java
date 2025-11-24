package com.example.urlshortener.infra.adapter.output.persistence;

import com.example.urlshortener.core.model.ShortUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;

@SpringBootTest
@Testcontainers
@DisplayName("Cassandra Integration Tests")
class CassandraIntegrationTest {

    @Container
    static CassandraContainer<?> cassandra = new CassandraContainer<>(
            DockerImageName.parse("cassandra:4.1"))
            .withExposedPorts(9042);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cassandra.contact-points",
                () -> cassandra.getHost() + ":" + cassandra.getMappedPort(9042));
        registry.add("spring.cassandra.local-datacenter", () -> "datacenter1");
        registry.add("spring.cassandra.keyspace-name", () -> "url_shortener");
        registry.add("spring.cassandra.schema-action", () -> "create-if-not-exists");
    }

    @Autowired
    private CassandraUrlRepository repository;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    private static final String TEST_ID = "test123";
    private static final String TEST_URL = "https://www.example.com/test";

    @BeforeEach
    void setUp() {
        // Clean up before each test (optional, depends on test isolation needs)
    }

    @Test
    @DisplayName("Should save and retrieve ShortUrl")
    void shouldSaveAndRetrieve() {
        // Given
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());

        // When
        repository.save(shortUrl);
        Optional<ShortUrl> retrieved = repository.findById(TEST_ID);

        // Then
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().id()).isEqualTo(TEST_ID);
        assertThat(retrieved.get().originalUrl()).isEqualTo(TEST_URL);
        assertThat(retrieved.get().createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Should return empty Optional for non-existent ID")
    void shouldReturnEmptyForNonExistentId() {
        // When
        Optional<ShortUrl> result = repository.findById("nonexistent999");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should persist multiple URLs")
    void shouldPersistMultipleUrls() {
        // Given
        ShortUrl url1 = new ShortUrl("id1", "https://example1.com", LocalDateTime.now());
        ShortUrl url2 = new ShortUrl("id2", "https://example2.com", LocalDateTime.now());
        ShortUrl url3 = new ShortUrl("id3", "https://example3.com", LocalDateTime.now());

        // When
        repository.save(url1);
        repository.save(url2);
        repository.save(url3);

        // Then
        assertThat(repository.findById("id1")).isPresent();
        assertThat(repository.findById("id2")).isPresent();
        assertThat(repository.findById("id3")).isPresent();
    }

    @Test
    @DisplayName("Should handle URLs with special characters")
    void shouldHandleSpecialCharacters() {
        // Given
        String urlWithSpecialChars = "https://example.com/path?param=value&other=123#anchor";
        ShortUrl shortUrl = new ShortUrl("special123", urlWithSpecialChars, LocalDateTime.now());

        // When
        repository.save(shortUrl);
        Optional<ShortUrl> retrieved = repository.findById("special123");

        // Then
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().originalUrl()).isEqualTo(urlWithSpecialChars);
    }
}
