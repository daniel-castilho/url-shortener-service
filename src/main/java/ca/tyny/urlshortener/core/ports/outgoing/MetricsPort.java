package ca.tyny.urlshortener.core.ports.outgoing;

import java.time.Duration;

/**
 * Port for recording application metrics.
 * Allows the core domain to track business events without depending on
 * infrastructure.
 */
public interface MetricsPort {

    void recordUrlShortened();

    void recordCacheHit();

    void recordCacheMiss();

    void recordBloomFilterRejection();

    void recordIdGeneration(Duration duration);

    void recordUrlRetrieval(Duration duration);

    void recordUrlExpired();

    void recordMigrationApplied();

    void recordMigrationFailed();
}
