package ca.tyny.urlshortener.infra.adapter.output.persistence.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ShortUrlEntityTest {

    @Test
    @DisplayName("Should create entity with all-args constructor")
    void shouldCreateEntityWithAllArgs() {
        LocalDateTime now = LocalDateTime.now();
        ShortUrlEntity entity = new ShortUrlEntity("abc123", "https://example.com", "hash123", now, "user1", true);

        assertThat(entity.getId()).isEqualTo("abc123");
        assertThat(entity.getOriginalUrl()).isEqualTo("https://example.com");
        assertThat(entity.getUrlHash()).isEqualTo("hash123");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUserId()).isEqualTo("user1");
        assertThat(entity.isCustomAlias()).isTrue();
    }

    @Test
    @DisplayName("Should create entity with no-args constructor")
    void shouldCreateEntityWithNoArgs() {
        ShortUrlEntity entity = new ShortUrlEntity();

        assertThat(entity.getId()).isNull();
        assertThat(entity.getOriginalUrl()).isNull();
        assertThat(entity.getUrlHash()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUserId()).isNull();
        assertThat(entity.isCustomAlias()).isFalse();
    }

    @Test
    @DisplayName("Should set and get all fields")
    void shouldSetAndGetAllFields() {
        LocalDateTime now = LocalDateTime.now();
        ShortUrlEntity entity = new ShortUrlEntity();

        entity.setId("abc123");
        entity.setOriginalUrl("https://example.com");
        entity.setUrlHash("hash123");
        entity.setCreatedAt(now);
        entity.setUserId("user1");
        entity.setCustomAlias(true);

        assertThat(entity.getId()).isEqualTo("abc123");
        assertThat(entity.getOriginalUrl()).isEqualTo("https://example.com");
        assertThat(entity.getUrlHash()).isEqualTo("hash123");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUserId()).isEqualTo("user1");
        assertThat(entity.isCustomAlias()).isTrue();
    }

    @Test
    @DisplayName("expiresAt defaults to null and is settable")
    void expiresAtDefaultsToNullAndIsSettable() {
        ShortUrlEntity entity = new ShortUrlEntity();
        assertThat(entity.getExpiresAt()).isNull();

        java.time.Instant expiry = java.time.Instant.parse("2026-12-31T23:59:59Z");
        entity.setExpiresAt(expiry);
        assertThat(entity.getExpiresAt()).isEqualTo(expiry);

        ShortUrlEntity viaAllArgs = new ShortUrlEntity("abc123", "https://example.com", "hash123", LocalDateTime.now(),
                "user1", true, 0, expiry);
        assertThat(viaAllArgs.getExpiresAt()).isEqualTo(expiry);
    }
}
