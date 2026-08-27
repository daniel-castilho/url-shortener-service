package ca.tyny.urlshortener.infra.adapter.output.redis;

import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("RedisUrlCache Tests")
@SuppressWarnings("unchecked")
class RedisUrlCacheTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RBloomFilter<String> bloomFilter;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private MetricsPort metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RedisUrlCache cache;

    private static final String TEST_ID = "abc123";
    private static final String TEST_URL = "https://www.example.com";

    @BeforeEach
    void setUp() {
        when(redisson.<String>getBloomFilter(anyString())).thenReturn(bloomFilter);
        when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache = new RedisUrlCache(redisTemplate, redisson, metrics, objectMapper);
    }

    @Test
    @DisplayName("Should return null when Bloom Filter says ID doesn't exist")
    void shouldReturnNullWhenBloomFilterSaysNotExists() {
        // Given
        when(bloomFilter.contains(TEST_ID)).thenReturn(false);

        // When
        CachedUrlValue result = cache.get(TEST_ID);

        // Then
        assertThat(result).isNull();
        verify(bloomFilter).contains(TEST_ID);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("Should get from Redis when Bloom Filter says ID exists")
    void shouldGetFromRedisWhenBloomFilterSaysExists() {
        // Given
        when(bloomFilter.contains(TEST_ID)).thenReturn(true);
        String encoded;
        try {
            encoded = objectMapper.writeValueAsString(Map.of("u", TEST_URL));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(valueOperations.get("url:" + TEST_ID)).thenReturn(encoded);

        // When
        CachedUrlValue result = cache.get(TEST_ID);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.originalUrl()).isEqualTo(TEST_URL);
        assertThat(result.expiresAt()).isNull();
        verify(bloomFilter).contains(TEST_ID);
        verify(valueOperations).get("url:" + TEST_ID);
    }

    @Test
    @DisplayName("Should decode expiry from Redis")
    void shouldDecodeExpiryFromRedis() {
        // Given
        when(bloomFilter.contains(TEST_ID)).thenReturn(true);
        long epoch = Instant.now().plusSeconds(3600).getEpochSecond();
        String encoded;
        try {
            encoded = objectMapper.writeValueAsString(Map.of("u", TEST_URL, "e", epoch));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(valueOperations.get("url:" + TEST_ID)).thenReturn(encoded);

        // When
        CachedUrlValue result = cache.get(TEST_ID);

        // Then
        assertThat(result.originalUrl()).isEqualTo(TEST_URL);
        assertThat(result.expiresAt().getEpochSecond()).isEqualTo(epoch);
    }

    @Test
    @DisplayName("Should put URL in Redis, Bloom Filter, and local cache")
    void shouldPutUrlInAllLayers() {
        // When
        cache.put(TEST_ID, new CachedUrlValue(TEST_URL, null));

        // Then
        verify(bloomFilter).add(TEST_ID);
        verify(valueOperations).set(eq("url:" + TEST_ID), argThat(s -> s.contains(TEST_URL)), any(Duration.class));

        // Verify local cache was populated (subsequent get should hit local cache)
        when(bloomFilter.contains(TEST_ID)).thenReturn(true);
        Cache<String, CachedUrlValue> localCache = (Cache<String, CachedUrlValue>) ReflectionTestUtils.getField(cache, "localCache");
        assertThat(localCache.getIfPresent(TEST_ID).originalUrl()).isEqualTo(TEST_URL);
    }

    @Test
    @DisplayName("Should use TTL with jitter for never-expiring links")
    void shouldUseTtlWithJitter() {
        // When
        cache.put(TEST_ID, new CachedUrlValue(TEST_URL, null));

        // Then
        verify(valueOperations).set(eq("url:" + TEST_ID), anyString(), argThat(duration -> duration.toHours() == 24
                && duration.toSeconds() >= 86400 && duration.toSeconds() <= 86460));
    }

    @Test
    @DisplayName("Should cap TTL at link expiry when expiry is sooner than base TTL")
    void shouldCapTtlAtExpiry() {
        // Given
        Instant expiresAt = Instant.now().plusSeconds(30);

        // When
        cache.put(TEST_ID, new CachedUrlValue(TEST_URL, expiresAt));

        // Then
        verify(valueOperations).set(eq("url:" + TEST_ID), anyString(),
                argThat(duration -> duration.toSeconds() >= 28 && duration.toSeconds() <= 30));
    }

    @Test
    @DisplayName("Should not cache an already-expired link")
    void shouldNotCacheAlreadyExpiredLink() {
        // When
        cache.put(TEST_ID, new CachedUrlValue(TEST_URL, Instant.now().minusSeconds(60)));

        // Then
        verify(bloomFilter, never()).add(TEST_ID);
        verify(valueOperations, never()).set(eq("url:" + TEST_ID), anyString(), any(Duration.class));
    }
}