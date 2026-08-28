package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.CodeGenerationException;
import ca.tyny.urlshortener.core.exception.InvalidDestinationException;
import ca.tyny.urlshortener.core.exception.ShortCodeCollisionException;
import ca.tyny.urlshortener.core.exception.UrlExpiredException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.validation.ReservedWordsValidator;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlShortenerService Tests")
class UrlShortenerServiceTest {

    @Mock
    private UrlRepositoryPort urlRepository;

    @Mock
    private UrlCachePort urlCache;

    @Mock
    private MetricsPort metrics;

    @Mock
    private UrlIdGenerator urlIdGenerator;

    @Mock
    private QuotaService quotaService;

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private ca.tyny.urlshortener.core.validation.ReservedWordsValidator reservedWordsValidator;

    @Mock
    private UrlValidator urlValidator;

    private Base62CodeGenerator base62CodeGenerator;

    private UrlShortenerService service;

    private static final String TEST_URL = "https://www.example.com/very/long/url";
    private static final String TEST_ID = "abc123";

    @BeforeEach
    void setUp() {
        base62CodeGenerator = new Base62CodeGenerator(7);
        lenient().doNothing().when(urlValidator).validate(anyString());
        service = new UrlShortenerService(urlRepository, urlCache, metrics, urlIdGenerator,
                base62CodeGenerator, quotaService, userRepository, reservedWordsValidator, urlValidator);
    }

    @Test
    @DisplayName("Should shorten URL using Base62 code generator")
    void shouldShortenUrl() {
        // When
        ShortUrl result = service.shorten(TEST_URL);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).hasSize(7);
        assertThat(result.id()).matches("^[0-9A-Za-z]+$");
        assertThat(result.originalUrl()).isEqualTo(TEST_URL);

        verify(urlRepository).save(any(ShortUrl.class));
    }

    @Test
    @DisplayName("Should pass custom alias and user ID to Generator")
    void shouldPassParamsToGenerator() {
        // Given
        String customAlias = "my-alias";
        String userId = "user123";
        when(urlIdGenerator.generateId(customAlias, userId)).thenReturn(customAlias);

        // When
        ShortUrl result = service.shorten(TEST_URL, customAlias, userId);

        // Then
        assertThat(result.id()).isEqualTo(customAlias);
        assertThat(result.userId()).isEqualTo(userId);

        verify(urlIdGenerator).generateId(customAlias, userId);
    }

    @Test
    @DisplayName("Should retry on collision and succeed")
    void shouldRetryOnCollision() {
        // Given: first save throws (collision), second succeeds
        doThrow(new ShortCodeCollisionException("abc123"))
                .doNothing()
                .when(urlRepository).save(any(ShortUrl.class));

        // When
        ShortUrl result = service.shorten(TEST_URL);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).hasSize(7);
        verify(urlRepository, times(2)).save(any(ShortUrl.class));
    }

    @Test
    @DisplayName("Should throw CodeGenerationException when retries exhausted")
    void shouldThrowOnRetryExhaustion() {
        // Given: every save throws collision
        doThrow(new ShortCodeCollisionException("abc123"))
                .when(urlRepository).save(any(ShortUrl.class));

        // When/Then
        assertThatThrownBy(() -> service.shorten(TEST_URL))
                .isInstanceOf(CodeGenerationException.class)
                .hasMessageContaining("Failed to generate a unique code");

        verify(urlRepository, times(UrlShortenerService.MAX_COLLISION_RETRIES + 1)).save(any(ShortUrl.class));
    }

@Test
    @DisplayName("Should get original URL from cache (Cache Hit)")
    void shouldGetOriginalUrlFromCache() {
        // Given
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null)));

        // When
        String result = service.getOriginalUrl(TEST_ID);

        // Then
        assertThat(result).isEqualTo(TEST_URL);
        verify(urlCache).lookup(TEST_ID);
        verify(urlRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should get original URL from DB and populate cache (Cache Miss)")
    void shouldGetOriginalUrlFromDbAndPopulateCache() {
        // Given
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
        when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(shortUrl));

        // When
        String result = service.getOriginalUrl(TEST_ID);

        // Then
        assertThat(result).isEqualTo(TEST_URL);
        verify(urlCache).lookup(TEST_ID);
        verify(urlRepository).findById(TEST_ID);
        verify(urlCache).put(TEST_ID, new CachedUrlValue(TEST_URL, null));
    }

    @Test
    @DisplayName("Should throw UrlExpiredException and not populate cache when short URL is expired")
    void shouldThrowUrlExpiredWhenShortUrlExpired() {
        // Given
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
        ShortUrl expired = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now())
                .withExpiresAt(Instant.now().minusSeconds(60));
        when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(expired));

        // When
        assertThatThrownBy(() -> service.getOriginalUrl(TEST_ID))
                .isInstanceOf(UrlExpiredException.class)
                .hasMessageContaining(TEST_ID);

        // Then
        verify(urlCache, never()).put(eq(TEST_ID), any(CachedUrlValue.class));
        verify(urlRepository).findById(TEST_ID);
        verify(metrics).recordUrlExpired();
    }

    @Test
    @DisplayName("Should serve a short URL that has not expired")
    void shouldServeNonExpiredShortUrl() {
        // Given
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now())
                .withExpiresAt(Instant.now().plusSeconds(3600));
        when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(shortUrl));

        // When
        String result = service.getOriginalUrl(TEST_ID);

        // Then
        assertThat(result).isEqualTo(TEST_URL);
        verify(urlRepository).findById(TEST_ID);
        verify(urlCache).put(TEST_ID, new CachedUrlValue(TEST_URL, shortUrl.expiresAt()));
    }

    @Test
    @DisplayName("Should record id generation duration metric on shorten")
    void shouldRecordIdGenerationMetric() {
        service.shorten(TEST_URL);

        verify(metrics).recordIdGeneration(any(Duration.class));
    }

    @Test
    @DisplayName("Should record url retrieval duration metric on cache hit")
    void shouldRecordUrlRetrievalMetricOnHit() {
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null)));

        service.getOriginalUrl(TEST_ID);

        verify(metrics).recordUrlRetrieval(any(Duration.class));
    }

    @Test
    @DisplayName("Should serve a cached value that has not expired")
    void shouldServeNonExpiredCachedValue() {
        // Given
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, Instant.now().plusSeconds(3600))));

        // When
        String result = service.getOriginalUrl(TEST_ID);

        // Then
        assertThat(result).isEqualTo(TEST_URL);
        verify(urlRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should throw UrlExpiredException for an expired cached value")
    void shouldThrowUrlExpiredForExpiredCachedValue() {
        // Given
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, Instant.now().minusSeconds(60))));

        // When
        assertThatThrownBy(() -> service.getOriginalUrl(TEST_ID))
                .isInstanceOf(UrlExpiredException.class);

        // Then
        verify(urlRepository, never()).findById(any());
        verify(metrics).recordUrlExpired();
    }

    @Test
    @DisplayName("Should record url retrieval duration metric on cache miss")
    void shouldRecordUrlRetrievalMetricOnMiss() {
        when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
        when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(shortUrl));

        service.getOriginalUrl(TEST_ID);

        verify(metrics).recordUrlRetrieval(any(Duration.class));
    }

    @Test
    @DisplayName("Should propagate expiry onto an auto-generated short URL")
    void shouldPropagateExpiresAtOnAutoCode() {
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);

        ShortUrl result = service.shorten(TEST_URL, null, null, expiry);

        assertThat(result.expiresAt()).isEqualTo(expiry);
        org.mockito.ArgumentCaptor<ShortUrl> captor = org.mockito.ArgumentCaptor.forClass(ShortUrl.class);
        verify(urlRepository).save(captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(expiry);
    }

    @Test
    @DisplayName("Should propagate expiry onto a vanity alias short URL")
    void shouldPropagateExpiresAtOnVanityAlias() {
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
        String customAlias = "my-alias";
        String userId = "user123";
        when(urlIdGenerator.generateId(customAlias, userId)).thenReturn(customAlias);

        ShortUrl result = service.shorten(TEST_URL, customAlias, userId, expiry);

        assertThat(result.id()).isEqualTo(customAlias);
        assertThat(result.expiresAt()).isEqualTo(expiry);
    }

    @Test
    @DisplayName("Should leave expiresAt null when no TTL is provided")
    void shouldLeaveExpiresAtNullByDefault() {
        ShortUrl result = service.shorten(TEST_URL);

        assertThat(result.expiresAt()).isNull();
    }
}
