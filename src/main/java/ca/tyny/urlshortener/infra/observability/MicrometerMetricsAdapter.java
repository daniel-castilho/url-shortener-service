package ca.tyny.urlshortener.infra.observability;

import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing MetricsPort using Micrometer. Tracks business metrics for monitoring and
 * observability.
 */
@Component
public class MicrometerMetricsAdapter implements MetricsPort {

  private final Timer idGenerationTimer;
  private final Timer urlRetrievalTimer;
  private final Timer shortenLatencyTimer;
  private final Timer redirectLatencyTimer;
  private final Counter urlsShortenedCounter;
  private final Counter redirectsCounter;
  private final Counter cacheHitsCounter;
  private final Counter cacheMissesCounter;
  private final Counter bloomFilterRejectionsCounter;
  private final Counter urlsExpiredCounter;
  private final Counter migrationsAppliedCounter;
  private final Counter migrationsFailedCounter;
  private final Counter ssrfBlockedCounter;
  private final Counter rateLimitExceededCounter;
  private final Counter vanityUrlsCreatedCounter;
  private final Counter domainsClaimedCounter;
  private final Counter domainsVerifiedCounter;
  private final Counter customDomainsCreatedCounter;

  public MicrometerMetricsAdapter(io.micrometer.core.instrument.MeterRegistry registry) {
    this.idGenerationTimer =
        Timer.builder("id.generation.duration")
            .description("Time to generate a short code")
            .tag("service", "url-shortener")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);

    this.urlRetrievalTimer =
        Timer.builder("url.retrieval.duration")
            .description("Time to retrieve a short URL")
            .tag("service", "url-shortener")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);

    this.shortenLatencyTimer =
        Timer.builder("shorten.latency")
            .description("End-to-end latency for URL shortening operation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .tag("operation", "shorten")
            .register(registry);

    this.redirectLatencyTimer =
        Timer.builder("redirect.latency")
            .description("End-to-end latency for redirect operation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .tag("operation", "redirect")
            .register(registry);

    this.urlsShortenedCounter =
        Counter.builder("urls.shortened.total")
            .description("Total number of URLs shortened")
            .tag("service", "url-shortener")
            .register(registry);

    this.redirectsCounter =
        Counter.builder("redirects.total")
            .description("Total number of redirects performed")
            .tag("service", "url-shortener")
            .register(registry);

    this.cacheHitsCounter =
        Counter.builder("cache.hits.total")
            .description("Total number of cache hits")
            .tag("cache", "redis")
            .register(registry);

    this.cacheMissesCounter =
        Counter.builder("cache.misses.total")
            .description("Total number of cache misses")
            .tag("cache", "redis")
            .register(registry);

    this.bloomFilterRejectionsCounter =
        Counter.builder("bloomfilter.rejections.total")
            .description("Total number of requests rejected by Bloom Filter")
            .tag("protection", "cache-penetration")
            .register(registry);

    this.urlsExpiredCounter =
        Counter.builder("urls.expired.total")
            .description("Total number of short URLs that expired before being resolved")
            .tag("service", "url-shortener")
            .register(registry);

    this.migrationsAppliedCounter =
        Counter.builder("schema.migrations.applied.total")
            .description("Total number of schema migrations applied")
            .tag("service", "url-shortener")
            .register(registry);

    this.migrationsFailedCounter =
        Counter.builder("schema.migrations.failed.total")
            .description("Total number of failed schema migrations")
            .tag("service", "url-shortener")
            .register(registry);

    this.ssrfBlockedCounter =
        Counter.builder("security.ssrf.blocked.total")
            .description("Total number of destination URLs blocked by SSRF protection")
            .tag("service", "url-shortener")
            .register(registry);

    this.rateLimitExceededCounter =
        Counter.builder("rate.limit.exceeded.total")
            .description("Total number of requests rejected by rate limiter")
            .tag("service", "url-shortener")
            .register(registry);

    this.vanityUrlsCreatedCounter =
        Counter.builder("vanity.urls.created.total")
            .description("Total number of vanity URLs created")
            .tag("service", "url-shortener")
            .register(registry);

    this.domainsClaimedCounter =
        Counter.builder("domains.claimed.total")
            .description("Total number of custom domains claimed")
            .tag("service", "url-shortener")
            .register(registry);

    this.domainsVerifiedCounter =
        Counter.builder("domains.verified.total")
            .description("Total number of custom domains verified")
            .tag("service", "url-shortener")
            .register(registry);

    this.customDomainsCreatedCounter =
        Counter.builder("custom.domains.created.total")
            .description("Total number of links created under custom domains")
            .tag("service", "url-shortener")
            .register(registry);
  }

  @Override
  public void recordUrlShortened() {
    urlsShortenedCounter.increment();
  }

  @Override
  public void recordRedirect() {
    redirectsCounter.increment();
  }

  @Override
  public void recordCacheHit() {
    cacheHitsCounter.increment();
  }

  @Override
  public void recordCacheMiss() {
    cacheMissesCounter.increment();
  }

  @Override
  public void recordBloomFilterRejection() {
    bloomFilterRejectionsCounter.increment();
  }

  @Override
  public void recordIdGeneration(Duration duration) {
    idGenerationTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordUrlRetrieval(Duration duration) {
    urlRetrievalTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordShortenLatency(Duration duration) {
    shortenLatencyTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordRedirectLatency(Duration duration) {
    redirectLatencyTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordUrlExpired() {
    urlsExpiredCounter.increment();
  }

  @Override
  public void recordMigrationApplied() {
    migrationsAppliedCounter.increment();
  }

  @Override
  public void recordMigrationFailed() {
    migrationsFailedCounter.increment();
  }

  @Override
  public void recordSsrfBlocked() {
    ssrfBlockedCounter.increment();
  }

  @Override
  public void recordRateLimitExceeded() {
    rateLimitExceededCounter.increment();
  }

  @Override
  public void recordVanityUrlCreated() {
    vanityUrlsCreatedCounter.increment();
  }

  @Override
  public void recordDomainClaimed() {
    domainsClaimedCounter.increment();
  }

  @Override
  public void recordDomainVerified() {
    domainsVerifiedCounter.increment();
  }

  @Override
  public void recordCustomDomainCreated() {
    customDomainsCreatedCounter.increment();
  }
}
