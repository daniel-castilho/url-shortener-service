package ca.tyny.urlshortener.infra.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerMetricsAdapterTest {

    private MeterRegistry registry;
    private MicrometerMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new MicrometerMetricsAdapter(registry);
    }

    @Test
    @DisplayName("Should register id.generation.duration timer with percentiles")
    void shouldRegisterIdGenerationTimer() {
        Timer timer = registry.find("id.generation.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.getId().getTag("service")).isEqualTo("url-shortener");
    }

    @Test
    @DisplayName("Should register url.retrieval.duration timer with percentiles")
    void shouldRegisterUrlRetrievalTimer() {
        Timer timer = registry.find("url.retrieval.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.getId().getTag("service")).isEqualTo("url-shortener");
    }

    @Test
    @DisplayName("Should record id generation duration")
    void shouldRecordIdGenerationDuration() {
        adapter.recordIdGeneration(Duration.ofMillis(5));

        Timer timer = registry.find("id.generation.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should record url retrieval duration")
    void shouldRecordUrlRetrievalDuration() {
        adapter.recordUrlRetrieval(Duration.ofMillis(3));

        Timer timer = registry.find("url.retrieval.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }
}