package com.example.urlshortener.infra.observability;

import com.example.urlshortener.core.ports.outgoing.MetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing MetricsPort using Micrometer.
 * Tracks business metrics for monitoring and observability.
 */
@Component
public class MicrometerMetricsAdapter implements MetricsPort {

    private final Counter urlsShortenedCounter;
    private final Counter cacheHitsCounter;
    private final Counter cacheMissesCounter;
    private final Counter bloomFilterRejectionsCounter;

    public MicrometerMetricsAdapter(MeterRegistry registry) {
        this.urlsShortenedCounter = Counter.builder("urls.shortened.total")
                .description("Total number of URLs shortened")
                .tag("service", "url-shortener")
                .register(registry);

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
    }

    @Override
    public void recordUrlShortened() {
        urlsShortenedCounter.increment();
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
}
