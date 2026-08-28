package ca.tyny.urlshortener.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortUrlTest {

    private static final String ID = "abc123";
    private static final String URL = "https://example.com";
    private static final Instant EXPIRY = Instant.parse("2026-12-31T23:59:59Z");

    private ShortUrl base() {
        return new ShortUrl(ID, URL, LocalDateTime.now(), "user1", true, 0, null, null, null, null, null);
    }

    @Test
    @DisplayName("expiresAt defaults to null (never expires)")
    void expiresAtDefaultsToNull() {
        assertThat(base().expiresAt()).isNull();
        assertThat(new ShortUrl(ID, URL, LocalDateTime.now(), null, false, 0, null, null, null, null, null).expiresAt()).isNull();
    }

    @Test
    @DisplayName("isExpired returns false when there is no expiry")
    void neverExpiringLinkIsNotExpired() {
        ShortUrl shortUrl = base();
        assertThat(shortUrl.isExpired(Instant.now().plusSeconds(3600))).isFalse();
        assertThat(shortUrl.isExpired(Instant.now().minusSeconds(3600))).isFalse();
    }

    @Test
    @DisplayName("isExpired returns false while now is before the expiry")
    void notExpiredWhenNowIsBeforeExpiry() {
        ShortUrl shortUrl = base().withExpiresAt(EXPIRY);
        assertThat(shortUrl.isExpired(EXPIRY.minusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("isExpired returns true at and after the expiry instant")
    void expiredAtAndAfterExpiryInstant() {
        ShortUrl shortUrl = base().withExpiresAt(EXPIRY);
        assertThat(shortUrl.isExpired(EXPIRY)).isTrue();
        assertThat(shortUrl.isExpired(EXPIRY.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("withExpiresAt preserves every other field")
    void withExpiresAtPreservesFields() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        ShortUrl original = new ShortUrl(ID, URL, createdAt, "user1", true, 7, null, null, null, null, null);

        ShortUrl withExpiry = original.withExpiresAt(EXPIRY);

        assertThat(withExpiry.id()).isEqualTo(ID);
        assertThat(withExpiry.originalUrl()).isEqualTo(URL);
        assertThat(withExpiry.createdAt()).isEqualTo(createdAt);
        assertThat(withExpiry.userId()).isEqualTo("user1");
        assertThat(withExpiry.isCustomAlias()).isTrue();
        assertThat(withExpiry.clickCount()).isEqualTo(7);
        assertThat(withExpiry.expiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("withClickCount preserves the expiry")
    void withClickCountPreservesExpiry() {
        ShortUrl shortUrl = base().withExpiresAt(EXPIRY).withClickCount(42);
        assertThat(shortUrl.clickCount()).isEqualTo(42);
        assertThat(shortUrl.expiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("isArchived returns false when deletedAt is null")
    void isArchivedReturnsFalseWhenDeletedAtIsNull() {
        assertThat(base().isArchived()).isFalse();
    }

    @Test
    @DisplayName("isArchived returns true when deletedAt is set")
    void isArchivedReturnsTrueWhenDeletedAtIsSet() {
        ShortUrl archived = base().archived();
        assertThat(archived.isArchived()).isTrue();
    }
}