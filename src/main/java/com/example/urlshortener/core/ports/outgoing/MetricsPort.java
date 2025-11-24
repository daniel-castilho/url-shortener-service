package com.example.urlshortener.core.ports.outgoing;

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
}
