package ca.tyny.urlshortener;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.time.LocalDateTime;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("Metrics E2E Tests")
class MetricsIT extends BaseIntegrationTest {

        @LocalServerPort
        private int port;

        @MockitoBean
        private RateLimiterPort rateLimiter;

        @Autowired
        private MongoTemplate mongoTemplate;

        @Autowired
        private MeterRegistry meterRegistry;

        @BeforeEach
        void setUp() {
                when(rateLimiter.tryAcquire(any(), anyString())).thenReturn(RateLimitVerdict.allow(100));
                RestAssured.port = port;
                RestAssured.basePath = "/";
        }

        @Test
        @DisplayName("Should register schema migration metrics")
        void shouldRegisterMigrationMetrics() {
                // When/Then
                assertThat(meterRegistry.find("schema.migrations.applied.total").counter()).isNotNull();
                assertThat(meterRegistry.find("schema.migrations.failed.total").counter()).isNotNull();
        }

        @Test
        @DisplayName("Should expose the analytics queue depth gauge")
        void shouldExposeAnalyticsQueueDepthGauge() {
                // When/Then
                assertThat(meterRegistry.find("analytics.queue.depth").gauge()).isNotNull();
        }

        @Test
        @DisplayName("Should count expired URL hits")
        void shouldCountExpiredUrls() {
                // Given
                String id = "expiredd";
                mongoTemplate.save(new ShortUrlEntity(id, "https://www.example.com/expired", "hash1234",
                                LocalDateTime.now(), null, false, 0, Instant.now().minusSeconds(60)));
                double before = meterRegistry.find("urls.expired.total").counter().count();

                // When: request the expired link
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/" + id)
                                .then()
                                .statusCode(410);

                // Then
                double after = meterRegistry.find("urls.expired.total").counter().count();
                assertThat(after).isEqualTo(before + 1.0);
        }
}