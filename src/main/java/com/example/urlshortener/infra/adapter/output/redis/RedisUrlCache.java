package com.example.urlshortener.infra.adapter.output.redis;

import com.example.urlshortener.core.ports.outgoing.MetricsPort;
import com.example.urlshortener.core.ports.outgoing.UrlCachePort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RedisUrlCache implements UrlCachePort {

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redisson;
    private final Cache<String, String> localCache;
    private final RBloomFilter<String> bloomFilter;
    private final MetricsPort metrics;

    private static final Duration BASE_TTL = Duration.ofHours(24);
    private static final long MAX_JITTER_SECONDS = 60;

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(RedisUrlCache.class);

    public RedisUrlCache(StringRedisTemplate redisTemplate, RedissonClient redisson, MetricsPort metrics) {
        this.redisTemplate = redisTemplate;
        this.redisson = redisson;
        this.metrics = metrics;

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
    public String get(String id) {
        // 1. Check Local Cache (Hot Keys)
        String localValue = localCache.getIfPresent(id);
        if (localValue != null) {
            return localValue;
        }

        // 2. Check Bloom Filter (Protection against Cache Penetration)
        try {
            if (!bloomFilter.contains(id)) {
                metrics.recordBloomFilterRejection();
                return null; // Definitely doesn't exist
            }
        } catch (org.redisson.client.RedisException e) {
            log.warn("Bloom Filter error during contains check. Skipping filter.", e);
            // Continue to Redis check if Bloom Filter fails
        }

        // 3. Check Redis
        String redisValue = redisTemplate.opsForValue().get("url:" + id);

        // Populate Local Cache if found
        if (redisValue != null) {
            localCache.put(id, redisValue);
        }

        return redisValue;
    }

    @Override
    public void put(String id, String originalUrl) {
        // Add to Bloom Filter
        try {
            bloomFilter.add(id);
        } catch (org.redisson.client.RedisException e) {
            log.warn("Bloom Filter error during add. Skipping filter.", e);
            // Continue without Bloom Filter if it fails
        }

        // Add to Redis with Jitter (Protection against Cache Stampede)
        long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_SECONDS);
        Duration ttl = BASE_TTL.plusSeconds(jitter);

        redisTemplate.opsForValue().set("url:" + id, originalUrl, ttl);

        // Add to Local Cache
        localCache.put(id, originalUrl);
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
