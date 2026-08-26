package ca.tyny.urlshortener.infra.adapter.output.persistence.mapper;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortUrlMapperTest {

    private ShortUrlMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ShortUrlMapper();
    }

    @Test
    @DisplayName("Should convert ShortUrl to ShortUrlEntity")
    void shouldConvertToEntity() {
        LocalDateTime now = LocalDateTime.now();
        ShortUrl domain = new ShortUrl("abc123", "https://example.com", now, "user1", true);

        ShortUrlEntity entity = mapper.toPersistence(domain);

        assertThat(entity.getId()).isEqualTo("abc123");
        assertThat(entity.getOriginalUrl()).isEqualTo("https://example.com");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUserId()).isEqualTo("user1");
        assertThat(entity.isCustomAlias()).isTrue();
        assertThat(entity.getUrlHash()).isNotBlank();
        assertThat(entity.getUrlHash()).hasSize(64); // SHA-256 hex length
    }

    @Test
    @DisplayName("Should generate SHA-256 hash for URL")
    void shouldGenerateSha256Hash() {
        ShortUrl domain = new ShortUrl("abc123", "https://example.com", LocalDateTime.now(), null, false);

        ShortUrlEntity entity = mapper.toPersistence(domain);

        // SHA-256 of "https://example.com"
        assertThat(entity.getUrlHash()).isEqualTo(
                "100680ad546ce6a577f42f52df33b4cfdca756859e664b8d7de329b150d09ce9");
    }

    @Test
    @DisplayName("Should throw when domain is null in toPersistence")
    void shouldThrowWhenDomainIsNullInToPersistence() {
        assertThatThrownBy(() -> mapper.toPersistence(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Should convert ShortUrlEntity to ShortUrl")
    void shouldConvertToDomain() {
        LocalDateTime now = LocalDateTime.now();
        ShortUrlEntity entity = new ShortUrlEntity("abc123", "https://example.com", "hash123", now, "user1", true);

        ShortUrl domain = mapper.toDomain(entity);

        assertThat(domain.id()).isEqualTo("abc123");
        assertThat(domain.originalUrl()).isEqualTo("https://example.com");
        assertThat(domain.createdAt()).isEqualTo(now);
        assertThat(domain.userId()).isEqualTo("user1");
        assertThat(domain.isCustomAlias()).isTrue();
    }

    @Test
    @DisplayName("Should throw when entity is null in toDomain")
    void shouldThrowWhenEntityIsNullInToDomain() {
        assertThatThrownBy(() -> mapper.toDomain(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Should handle round-trip conversion")
    void shouldHandleRoundTrip() {
        LocalDateTime now = LocalDateTime.now();
        ShortUrl original = new ShortUrl("abc123", "https://example.com", now, "user1", false);

        ShortUrlEntity entity = mapper.toPersistence(original);
        ShortUrl converted = mapper.toDomain(entity);

        assertThat(converted.id()).isEqualTo(original.id());
        assertThat(converted.originalUrl()).isEqualTo(original.originalUrl());
        assertThat(converted.createdAt()).isEqualTo(original.createdAt());
        assertThat(converted.userId()).isEqualTo(original.userId());
        assertThat(converted.isCustomAlias()).isEqualTo(original.isCustomAlias());
    }

    @Test
    @DisplayName("Should round-trip clickCount across domain and entity")
    void shouldRoundTripClickCount() {
        LocalDateTime now = LocalDateTime.now();
        ShortUrl domain = new ShortUrl("abc123", "https://example.com", now, "user1", true).withClickCount(42);

        ShortUrlEntity entity = mapper.toPersistence(domain);
        assertThat(entity.getClickCount()).isEqualTo(42);

        ShortUrl converted = mapper.toDomain(entity);
        assertThat(converted.clickCount()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should default clickCount to zero for legacy constructors")
    void shouldDefaultClickCountToZero() {
        ShortUrl viaFourArgs = new ShortUrl("abc123", "https://example.com", LocalDateTime.now(), "user1");
        ShortUrlEntity viaSixArgs =
                new ShortUrlEntity("abc123", "https://example.com", "hash", LocalDateTime.now(), "user1", true);

        assertThat(viaFourArgs.clickCount()).isZero();
        assertThat(viaSixArgs.getClickCount()).isZero();
    }
}
