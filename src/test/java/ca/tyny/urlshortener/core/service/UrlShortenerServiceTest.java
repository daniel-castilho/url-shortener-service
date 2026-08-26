package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.CodeGenerationException;
import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private Base62CodeGenerator base62CodeGenerator;

    private UrlShortenerService service;

    private static final String TEST_URL = "https://www.example.com/very/long/url";
    private static final String TEST_ID = "abc123";

    @BeforeEach
    void setUp() {
        base62CodeGenerator = new Base62CodeGenerator(7);
        service = new UrlShortenerService(urlRepository, urlCache, metrics, urlIdGenerator,
                base62CodeGenerator, quotaService, userRepository, reservedWordsValidator);
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
        doThrow(new RuntimeException("Duplicate key"))
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
        // Given: every save throws
        doThrow(new RuntimeException("Duplicate key"))
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
        when(urlCache.get(TEST_ID)).thenReturn(TEST_URL);

        // When
        String result = service.getOriginalUrl(TEST_ID);

        // Then
        assertThat(result).isEqualTo(TEST_URL);
        verify(urlCache).get(TEST_ID);
        verify(urlRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should get original URL from DB and populate cache (Cache Miss)")
    void shouldGetOriginalUrlFromDbAndPopulateCache() {
        // Given
        when(urlCache.get(TEST_ID)).thenReturn(null);
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
        when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(shortUrl));

        // When
        String result = service.getOriginalUrl(TEST_ID);

        // Then
        assertThat(result).isEqualTo(TEST_URL);
        verify(urlCache).get(TEST_ID);
        verify(urlRepository).findById(TEST_ID);
        verify(urlCache).put(TEST_ID, TEST_URL);
    }
}
