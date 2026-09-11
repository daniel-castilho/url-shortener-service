package ca.tyny.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.infra.adapter.output.redis.RedisUrlCache;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Validates that the L1 Caffeine cache is driven by {@code app.cache.l1-*} configuration (Epic 5
 * story 5.4) — the cache must honour the configured maximum size and TTL instead of the historical
 * hardcoded 100 items / 5s, while the Redis L2 fallback keeps lookups correct after L1 eviction.
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.cache.l1-max-size=2",
      "app.cache.l1-ttl=PT0.5S",
    })
@DisplayName("UrlCache L1 configuration (app.cache.l1-*)")
class UrlCachePropertiesIT extends BaseIntegrationTest {

  @Autowired private RedisUrlCache cache;

  @SuppressWarnings("unchecked")
  private Cache<String, CachedUrlValue> localCache() {
    return (Cache<String, CachedUrlValue>) ReflectionTestUtils.getField(cache, "localCache");
  }

  private CachedUrlValue value(String url) {
    return new CachedUrlValue(url, null);
  }

  @Test
  @DisplayName("L1 evicts beyond configured maximum size (size=2)")
  void l1EvictsBeyondConfiguredMaxSize() {
    cache.put("size-a", value("https://example.com/a"));
    cache.put("size-b", value("https://example.com/b"));
    cache.put("size-c", value("https://example.com/c"));
    // Caffeine evicts lazily during maintenance; cleanUp() forces the drain
    localCache().cleanUp();

    Cache<String, CachedUrlValue> l1 = localCache();
    assertThat(l1.estimatedSize()).as("L1 bounded to configured size=2").isEqualTo(2);
    // W-TinyLFU admission may reject the newest entry instead of evicting the oldest;
    // the contract is "at most 2 of 3 entries retained", not which specific one
    long retained =
        java.util.stream.Stream.of("size-a", "size-b", "size-c")
            .filter(id -> l1.getIfPresent(id) != null)
            .count();
    assertThat(retained).as("at most 2 of 3 entries retained under size=2").isLessThanOrEqualTo(2);
  }

  @Test
  @DisplayName("L1 entries expire after configured TTL (PT0.5S)")
  void l1EntriesExpireAfterConfiguredTtl() throws InterruptedException {
    cache.put("ttl-a", value("https://example.com/ttl"));
    assertThat(localCache().getIfPresent("ttl-a")).isNotNull();

    // Poll until the L1 entry expires per the configured PT0.5S write TTL
    for (int i = 0; i < 20 && localCache().getIfPresent("ttl-a") != null; i++) {
      Thread.sleep(100);
    }
    assertThat(localCache().getIfPresent("ttl-a"))
        .as("entry expired from L1 after PT0.5S")
        .isNull();
  }

  @Test
  @DisplayName("Lookup still hits via Redis L2 after L1 eviction")
  void lookupStillHitsViaRedisAfterL1Eviction() {
    cache.put("l2-a", value("https://example.com/l2"));
    cache.invalidateAllLocal(); // force L1 miss
    CacheLookup result = cache.lookup("l2-a");
    assertThat(result.absence()).isEqualTo(CacheLookup.Absence.NONE);
    assertThat(result.value()).isNotNull();
    assertThat(result.value().originalUrl()).isEqualTo("https://example.com/l2");
  }

  @Test
  @DisplayName("Bloom filter still guards unknown codes with config-driven init")
  void bloomStillGuardsUnknownCodes() {
    CacheLookup unknown = cache.lookup("never-added-code");
    assertThat(unknown.absence()).isEqualTo(CacheLookup.Absence.BLOOM_NEGATIVE);

    cache.put("bloom-known", value("https://example.com/bloom"));
    CacheLookup known = cache.lookup("bloom-known");
    assertThat(known.absence()).isEqualTo(CacheLookup.Absence.NONE);
  }
}
