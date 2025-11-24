package com.example.urlshortener.infra.adapter.output.redis;

import com.example.urlshortener.core.ports.outgoing.MetricsPort;
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

    private RedisUrlCache cache;

    private static final String TEST_ID = "abc123";
    private static final String TEST_URL = "https://www.example.com";

    @BeforeEach
    void setUp() {
        when(redisson.<String>getBloomFilter(anyString())).thenReturn(bloomFilter);
        when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache = new RedisUrlCache(redisTemplate, redisson, metrics);
    }

    @Test
    @DisplayName("Should return null when Bloom Filter says ID doesn't exist")
    void shouldReturnNullWhenBloomFilterSaysNotExists() {
        // Given
        when(bloomFilter.contains(TEST_ID)).thenReturn(false);

        // When
        String result = cache.get(TEST_ID);

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
        when(valueOperations.get("url:" + TEST_ID)).thenReturn(TEST_URL);

        // When
        String result = cache.get(TEST_ID);

        // Then
        assertThat(result).isEqualTo(TEST_URL);
        verify(bloomFilter).contains(TEST_ID);
        verify(valueOperations).get("url:" + TEST_ID);
    }

    @Test
    @DisplayName("Should put URL in Redis, Bloom Filter, and local cache")
    void shouldPutUrlInAllLayers() {
        // When
        cache.put(TEST_ID, TEST_URL);

        // Then
        verify(bloomFilter).add(TEST_ID);
        verify(valueOperations).set(eq("url:" + TEST_ID), eq(TEST_URL), any(Duration.class));

        // Verify local cache was populated (subsequent get should hit local cache)
        when(bloomFilter.contains(TEST_ID)).thenReturn(true);
        Cache<String, String> localCache = (Cache<String, String>) ReflectionTestUtils.getField(cache, "localCache");
        assertThat(localCache.getIfPresent(TEST_ID)).isEqualTo(TEST_URL);
    }

    @Test
    @DisplayName("Should use TTL with jitter")
    void shouldUseTtlWithJitter() {
        // When
        cache.put(TEST_ID, TEST_URL);

        // Then
        verify(valueOperations).set(eq("url:" + TEST_ID), eq(TEST_URL), argThat(duration -> duration.toHours() == 24
                && duration.toSeconds() >= 86400 && duration.toSeconds() <= 86460));
    }
}
