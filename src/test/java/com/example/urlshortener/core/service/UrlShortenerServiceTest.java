package com.example.urlshortener.core.service;

import com.example.urlshortener.core.exception.UrlNotFoundException;
import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.ports.outgoing.IdGeneratorPort;
import com.example.urlshortener.core.ports.outgoing.MetricsPort;
import com.example.urlshortener.core.ports.outgoing.UrlCachePort;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlShortenerService Tests")
class UrlShortenerServiceTest {

    @Mock
    private UrlRepositoryPort urlRepository;

    @Mock
    private IdGeneratorPort idGenerator;

    @Mock
    private UrlCachePort urlCache;

    @Mock
    private MetricsPort metrics;

    private UrlShortenerService service;

    private static final String TEST_URL = "https://www.example.com/very/long/url";
    private static final String TEST_ID = "abc123";

    @BeforeEach
    void setUp() {
        service = new UrlShortenerService(urlRepository, idGenerator, urlCache, metrics);
    }

    @Test
    @DisplayName("Should shorten URL successfully")
    void shouldShortenUrl() {
        // Given
        when(idGenerator.generateId()).thenReturn(TEST_ID);

        // When
        ShortUrl result = service.shorten(TEST_URL);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(TEST_ID);
        assertThat(result.originalUrl()).isEqualTo(TEST_URL);
        assertThat(result.createdAt()).isNotNull();

        verify(idGenerator).generateId();
        verify(urlRepository).save(any(ShortUrl.class));
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

    @Test
    @DisplayName("Should throw UrlNotFoundException when URL not found")
    void shouldThrowExceptionWhenUrlNotFound() {
        // Given
        when(urlCache.get(TEST_ID)).thenReturn(null);
        when(urlRepository.findById(TEST_ID)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> service.getOriginalUrl(TEST_ID))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessageContaining(TEST_ID);

        verify(urlCache).get(TEST_ID);
        verify(urlRepository).findById(TEST_ID);
        verify(urlCache, never()).put(any(), any());
    }
}
