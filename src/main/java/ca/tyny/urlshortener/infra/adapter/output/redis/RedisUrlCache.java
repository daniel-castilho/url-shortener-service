package ca.tyny.urlshortener.infra.adapter.output.redis;

import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RedisUrlCache implements UrlCachePort {

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redisson;
    private final Cache<String, CachedUrlValue> localCache;
    private final RBloomFilter<String> bloomFilter;
    private final MetricsPort metrics;
    private final ObjectMapper objectMapper;

    private static final Duration BASE_TTL = Duration.ofHours(24);
    private static final long MAX_JITTER_SECONDS = 60;

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(RedisUrlCache.class);

    public RedisUrlCache(StringRedisTemplate redisTemplate, RedissonClient redisson, MetricsPort metrics,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.redisson = redisson;
        this.metrics = metrics;
        this.objectMapper = objectMapper;

        // Caffeine Local Cache: 100 items, 5 seconds TTL
        this.localCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofSeconds(5))
                .build();

        // Bloom Filter: Expected 100M elements, 1% false positive probability
        this.bloomFilter = redisson.getBloomFilter("url_shortener:bloom_filter");
        try {
            this.bloomFilter.tryInit(100_000_000L, 0.01);
        } catch (org.redisson.client.RedisException e) {
            if (e.getMessage().contains("Bloom filter config has been changed")) {
                log.warn("Bloom Filter config changed. Re-initializing...");
                this.bloomFilter.delete();
                this.bloomFilter.tryInit(100_000_000L, 0.01);
            } else {
                throw e;
            }
        }
    }

    @Override
    public CacheLookup lookup(String id) {
        // 1. Check Local Cache (Hot Keys)
        CachedUrlValue localValue = localCache.getIfPresent(id);
        if (localValue != null) {
            return CacheLookup.hit(localValue);
        }

        // 2. Check Bloom Filter (Protection against Cache Penetration)
        try {
            if (!bloomFilter.contains(id)) {
                metrics.recordBloomFilterRejection();
                return CacheLookup.bloomNegative();
            }
        } catch (org.redisson.client.RedisException e) {
            log.warn("Bloom Filter error during contains check. Skipping filter.", e);
            // Continue to Redis check if Bloom Filter fails
        }

        // 3. Check Redis
        String redisValue = redisTemplate.opsForValue().get("url:" + id);

        if (redisValue == null) {
            return CacheLookup.miss();
        }

        CachedUrlValue decoded = decode(redisValue);
        if (decoded == null) {
            log.warn("Discarding malformed cache entry for id={}", id);
            return CacheLookup.miss();
        }

        // Populate Local Cache if found
        localCache.put(id, decoded);

        return CacheLookup.hit(decoded);
    }

    @Override
    public void put(String id, CachedUrlValue value) {
        Duration ttl = computeTtl(value);
        if (ttl.isZero() || ttl.isNegative()) {
            log.debug("Not caching id={}: link already expired", id);
            return;
        }

        // Add to Bloom Filter
        try {
            bloomFilter.add(id);
        } catch (org.redisson.client.RedisException e) {
            log.warn("Bloom Filter error during add. Skipping filter.", e);
            // Continue without Bloom Filter if it fails
        }

        // Add to Redis with TTL capped at the link expiry and jittered otherwise
        redisTemplate.opsForValue().set("url:" + id, encode(value), ttl);

        // Add to Local Cache
        localCache.put(id, value);
    }

    /**
     * TTL = BASE_TTL (24h) + jitter for never-expiring links; for expiring links the
     * TTL is capped at the remaining time so the key is evicted at or before expiry.
     * Already-expired links are not cached.
     */
    private Duration computeTtl(CachedUrlValue value) {
        if (value.expiresAt() == null) {
            long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_SECONDS);
            return BASE_TTL.plusSeconds(jitter);
        }

        Duration remaining = Duration.between(Instant.now(), value.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ZERO; // already expired -> caller must not cache
        }
        if (remaining.compareTo(BASE_TTL) < 0) {
            return remaining;
        }
        long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_SECONDS);
        return BASE_TTL.plusSeconds(jitter);
    }

    private String encode(CachedUrlValue value) {
        try {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("u", value.originalUrl());
            if (value.expiresAt() != null) {
                fields.put("e", value.expiresAt().getEpochSecond());
            }
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            log.error("Failed to encode cache value for id={}", value.originalUrl(), e);
            throw new IllegalStateException("Failed to encode cache value", e);
        }
    }

    private CachedUrlValue decode(String redisValue) {
        try {
            Map<String, Object> fields = objectMapper.readValue(redisValue, new TypeReference<Map<String, Object>>() {
            });
            String url = (String) fields.get("u");
            Object exp = fields.get("e");
            Instant expiresAt = exp == null ? null : Instant.ofEpochSecond(((Number) exp).longValue());
            return url == null ? null : new CachedUrlValue(url, expiresAt);
        } catch (Exception e) {
            return null;
        }
    }

    public void resetBloomFilter() {
        try {
            this.bloomFilter.delete();
            this.bloomFilter.tryInit(100_000_000L, 0.01);
        } catch (Exception e) {
            log.error("Failed to reset Bloom Filter", e);
        }
    }
}