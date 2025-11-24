package com.example.urlshortener.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Service for tracking custom business metrics using Micrometer.
 * Provides methods to record URL shortening operations, cache performance,
 * and ID generation metrics.
 */
@Component
public class MetricsService {

    private final Counter urlsShortenedCounter;
    private final Counter redirectsCounter;
    private final Counter cacheHitsCounter;
    private final Counter cacheMissesCounter;
    private final Counter bloomFilterRejectionsCounter;
    private final Timer idGenerationTimer;
    private final Timer urlRetrievalTimer;
    private final Timer shortenLatencyTimer;
    private final Timer redirectLatencyTimer;

    public MetricsService(MeterRegistry registry) {
        // URL Shortening Metrics
        this.urlsShortenedCounter = Counter.builder("urls.shortened.total")
                .description("Total number of URLs shortened")
                .tag("service", "url-shortener")
                .register(registry);

        // Redirect Metrics
        this.redirectsCounter = Counter.builder("redirects.total")
                .description("Total number of redirects performed")
                .tag("service", "url-shortener")
                .register(registry);

        // Cache Metrics
        this.cacheHitsCounter = Counter.builder("cache.hits.total")
                .description("Total number of cache hits")
                .tag("cache", "redis")
                .register(registry);

        this.cacheMissesCounter = Counter.builder("cache.misses.total")
                .description("Total number of cache misses")
                .tag("cache", "redis")
                .register(registry);

        this.bloomFilterRejectionsCounter = Counter.builder("bloomfilter.rejections.total")
                .description("Total number of requests rejected by Bloom Filter")
                .tag("protection", "cache-penetration")
                .register(registry);

        // Performance Timers
        this.idGenerationTimer = Timer.builder("id.generation.duration")
                .description("Time taken to generate a new ID")
                .tag("component", "id-generator")
                .register(registry);

        this.urlRetrievalTimer = Timer.builder("url.retrieval.duration")
                .description("Time taken to retrieve a URL")
                .tag("operation", "get")
                .register(registry);

        // Latency Timers with Percentiles
        this.shortenLatencyTimer = Timer.builder("shorten.latency")
                .description("End-to-end latency for URL shortening operation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .tag("operation", "shorten")
                .register(registry);

        this.redirectLatencyTimer = Timer.builder("redirect.latency")
                .description("End-to-end latency for redirect operation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .tag("operation", "redirect")
                .register(registry);
    }

    /**
     * Record a URL shortening operation
     */
    public void recordUrlShortened() {
        urlsShortenedCounter.increment();
    }

    /**
     * Record a redirect operation
     */
    public void recordRedirect() {
        redirectsCounter.increment();
    }

    /**
     * Record a cache hit
     */
    public void recordCacheHit() {
        cacheHitsCounter.increment();
    }

    /**
     * Record a cache miss
     */
    public void recordCacheMiss() {
        cacheMissesCounter.increment();
    }

    /**
     * Record a Bloom Filter rejection (cache penetration protection)
     */
    public void recordBloomFilterRejection() {
        bloomFilterRejectionsCounter.increment();
    }

    /**
     * Record ID generation time
     */
    public void recordIdGeneration(long durationMs) {
        idGenerationTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record URL retrieval time
     */
    public void recordUrlRetrieval(long durationMs) {
        urlRetrievalTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record end-to-end latency for URL shortening operation
     */
    public void recordShortenLatency(long durationMs) {
        shortenLatencyTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record end-to-end latency for redirect operation
     */
    public void recordRedirectLatency(long durationMs) {
        redirectLatencyTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute and time an ID generation operation
     */
    public <T> T timeIdGeneration(java.util.function.Supplier<T> operation) {
        return idGenerationTimer.record(operation);
    }

    /**
     * Execute and time a URL retrieval operation
     */
    public <T> T timeUrlRetrieval(java.util.function.Supplier<T> operation) {
        return urlRetrievalTimer.record(operation);
    }

    /**
     * Execute and time a URL shortening operation
     */
    public <T> T timeShortenOperation(java.util.function.Supplier<T> operation) {
        return shortenLatencyTimer.record(operation);
    }

    /**
     * Execute and time a redirect operation
     */
    public <T> T timeRedirectOperation(java.util.function.Supplier<T> operation) {
        return redirectLatencyTimer.record(operation);
    }
}
