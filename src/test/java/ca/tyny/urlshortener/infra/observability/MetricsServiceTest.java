package ca.tyny.urlshortener.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsServiceTest {

    private MeterRegistry registry;
    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new MetricsService(registry);
    }

    @Test
    @DisplayName("Should record URL shortened event")
    void shouldRecordUrlShortened() {
        metricsService.recordUrlShortened();

        Counter counter = registry.find("urls.shortened.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record redirect event")
    void shouldRecordRedirect() {
        metricsService.recordRedirect();

        Counter counter = registry.find("redirects.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record cache hit")
    void shouldRecordCacheHit() {
        metricsService.recordCacheHit();

        Counter counter = registry.find("cache.hits.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record cache miss")
    void shouldRecordCacheMiss() {
        metricsService.recordCacheMiss();

        Counter counter = registry.find("cache.misses.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record Bloom Filter rejection")
    void shouldRecordBloomFilterRejection() {
        metricsService.recordBloomFilterRejection();

        Counter counter = registry.find("bloomfilter.rejections.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record shorten latency")
    void shouldRecordShortenLatency() {
        metricsService.recordShortenLatency(100);

        Timer timer = registry.find("shorten.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should record redirect latency")
    void shouldRecordRedirectLatency() {
        metricsService.recordRedirectLatency(50);

        Timer timer = registry.find("redirect.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should time shorten operation")
    void shouldTimeShortenOperation() {
        String result = metricsService.timeShortenOperation(() -> "result");

        assertThat(result).isEqualTo("result");
        Timer timer = registry.find("shorten.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should time redirect operation")
    void shouldTimeRedirectOperation() {
        String result = metricsService.timeRedirectOperation(() -> "result");

        assertThat(result).isEqualTo("result");
        Timer timer = registry.find("redirect.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should register all metrics on construction")
    void shouldRegisterAllMetrics() {
        assertThat(registry.find("urls.shortened.total").counter()).isNotNull();
        assertThat(registry.find("redirects.total").counter()).isNotNull();
        assertThat(registry.find("cache.hits.total").counter()).isNotNull();
        assertThat(registry.find("cache.misses.total").counter()).isNotNull();
        assertThat(registry.find("bloomfilter.rejections.total").counter()).isNotNull();
        assertThat(registry.find("shorten.latency").timer()).isNotNull();
        assertThat(registry.find("redirect.latency").timer()).isNotNull();
    }
}
