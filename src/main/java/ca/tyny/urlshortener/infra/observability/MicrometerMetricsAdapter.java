package ca.tyny.urlshortener.infra.observability;

import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Adapter implementing MetricsPort using Micrometer.
 * Tracks business metrics for monitoring and observability.
 */
@Component
public class MicrometerMetricsAdapter implements MetricsPort {

    private final Timer idGenerationTimer;
    private final Timer urlRetrievalTimer;
    private final Counter urlsShortenedCounter;
    private final Counter cacheHitsCounter;
    private final Counter cacheMissesCounter;
    private final Counter bloomFilterRejectionsCounter;

    public MicrometerMetricsAdapter(io.micrometer.core.instrument.MeterRegistry registry) {
        this.idGenerationTimer = Timer.builder("id.generation.duration")
                .description("Time to generate a short code")
                .tag("service", "url-shortener")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.urlRetrievalTimer = Timer.builder("url.retrieval.duration")
                .description("Time to retrieve a short URL")
                .tag("service", "url-shortener")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

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

    @Override
    public void recordIdGeneration(Duration duration) {
        idGenerationTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordUrlRetrieval(Duration duration) {
        urlRetrievalTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
    }
}
