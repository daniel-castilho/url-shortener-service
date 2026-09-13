package ca.tyny.urlshortener.infra.adapter.output.redis;

import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.infra.config.properties.UrlCacheProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class RedisUrlCache implements UrlCachePort {

  private final StringRedisTemplate redisTemplate;
  private final RedissonClient redisson;
  private final Cache<String, CachedUrlValue> localCache;
  private final RBloomFilter<String> bloomFilter;
  private final UrlCacheProperties cacheProperties;
  private final MetricsPort metrics;
  private final ObjectMapper objectMapper;

  private static final Duration BASE_TTL = Duration.ofHours(24);
  private static final long MAX_JITTER_SECONDS = 60;

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(RedisUrlCache.class);

  public RedisUrlCache(
      StringRedisTemplate redisTemplate,
      RedissonClient redisson,
      MetricsPort metrics,
      ObjectMapper objectMapper,
      UrlCacheProperties cacheProperties) {
    this.redisTemplate = redisTemplate;
    this.redisson = redisson;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
    this.cacheProperties = cacheProperties;

    // Caffeine Local Cache (L1) — tuned via app.cache.l1-* (defaults: 100 items / 5s TTL)
    this.localCache =
        Caffeine.newBuilder()
            .maximumSize(cacheProperties.l1MaxSize())
            .expireAfterWrite(cacheProperties.l1Ttl())
            .build();

    // Bloom Filter — tuned via app.cache.bloom-* (defaults: 100M expected / 1% fpp)
    this.bloomFilter = redisson.getBloomFilter("url_shortener:bloom_filter");
    try {
      this.bloomFilter.tryInit(
          cacheProperties.bloomExpectedInsertions(),
          cacheProperties.bloomFalsePositiveProbability());
    } catch (org.redisson.client.RedisException e) {
      if (e.getMessage().contains("Bloom filter config has been changed")) {
        log.warn("Bloom Filter config changed. Re-initializing...");
        this.bloomFilter.delete();
        this.bloomFilter.tryInit(
            cacheProperties.bloomExpectedInsertions(),
            cacheProperties.bloomFalsePositiveProbability());
      } else {
        throw e;
      }
    }
  }

  /**
   * Cache key prefix, shape-versioned (REQ-CACHE-001): {@code url:v1:<id>}. The {@code v1} segment
   * versions the serialized {@link CachedUrlValue} shape ({@code u}/{@code e}/{@code d} JSON
   * fields). Whenever {@link #encode(CachedUrlValue)} changes the serialized shape, bump the
   * version in this prefix: new readers simply miss on stale {@code url:v<n-1>:} entries and
   * rebuild from the source, instead of decoding old-shape values with a new decoder until the old
   * TTLs expire.
   */
  private static final String KEY_PREFIX = "url:v1:";

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
    String redisValue;
    try {
      redisValue = redisTemplate.opsForValue().get(KEY_PREFIX + id);
    } catch (DataAccessException e) {
      // ADR 0005: L2 is degrade (skip, fall through) — an outage is a cache miss,
      // proceeds to MongoDB. Higher latency, zero client-visible failure.
      log.warn("Redis L2 lookup failed for id={}; degrading to MongoDB", id, e);
      return CacheLookup.miss();
    }

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
    try {
      redisTemplate.opsForValue().set(KEY_PREFIX + id, encode(value), ttl);
    } catch (DataAccessException e) {
      // ADR 0005: L2 is degrade (skip) — a Redis outage must not fail the caller.
      log.warn("Redis L2 put failed for id={}; continuing without it", id, e);
    }

    // Add to Local Cache
    localCache.put(id, value);
  }

  /**
   * TTL = BASE_TTL (24h) + jitter for never-expiring links; for expiring links the TTL is capped at
   * the remaining time so the key is evicted at or before expiry. Already-expired links are not
   * cached.
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
      if (value.domain() != null) {
        fields.put("d", value.domain());
      }
      return objectMapper.writeValueAsString(fields);
    } catch (Exception e) {
      log.error("Failed to encode cache value for id={}", value.originalUrl(), e);
      throw new IllegalStateException("Failed to encode cache value", e);
    }
  }

  private CachedUrlValue decode(String redisValue) {
    try {
      Map<String, Object> fields =
          objectMapper.readValue(redisValue, new TypeReference<Map<String, Object>>() {});
      String url = (String) fields.get("u");
      Object exp = fields.get("e");
      Instant expiresAt = exp == null ? null : Instant.ofEpochSecond(((Number) exp).longValue());
      String domain = (String) fields.get("d");
      return url == null ? null : new CachedUrlValue(url, expiresAt, domain);
    } catch (Exception e) {
      return null;
    }
  }

  public void resetBloomFilter() {
    try {
      this.bloomFilter.delete();
      this.bloomFilter.tryInit(
          cacheProperties.bloomExpectedInsertions(),
          cacheProperties.bloomFalsePositiveProbability());
    } catch (Exception e) {
      log.error("Failed to reset Bloom Filter", e);
    }
  }

  public void invalidateAllLocal() {
    this.localCache.invalidateAll();
  }

  @Override
  public void evict(String id) {
    // Delete from Redis (best-effort per ADR 0005 degrade policy)
    try {
      redisTemplate.delete(KEY_PREFIX + id);
    } catch (DataAccessException e) {
      log.warn("Redis L2 evict failed for id={}; continuing with L1 invalidation", id, e);
    }
    // Invalidate local Caffeine cache
    localCache.invalidate(id);
    // Note: Bloom filter entry is kept (harmless false positive -> extra DB hit)
    log.debug("Evicted cache for id={}", id);
  }
}
