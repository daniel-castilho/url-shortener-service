package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.restassured.RestAssured;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Metrics E2E Tests")
class MetricsIT extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private PrometheusMeterRegistry prometheusRegistry;

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
    mongoTemplate.save(
        new ShortUrlEntity(
            id,
            "https://www.example.com/expired",
            "hash1234",
            LocalDateTime.now(),
            null,
            false,
            0,
            Instant.now().minusSeconds(60)));
    double before = meterRegistry.find("urls.expired.total").counter().count();

    // When: request the expired link
    given().redirects().follow(false).when().get("/" + id).then().statusCode(410);

    // Then
    double after = meterRegistry.find("urls.expired.total").counter().count();
    assertThat(after).isEqualTo(before + 1.0);
  }

  @Test
  @DisplayName("Epic 2 business series are exported by the Prometheus scrape (playback)")
  void playbackExportsEpic2BusinessSeries() {
    // Drive the paths that own the hardened metrics, exactly once each:
    // 1) shorten a URL -> urls.shortened, id.generation.duration, shorten.latency
    String code =
        given()
            .contentType(io.restassured.http.ContentType.JSON)
            .body("{\"originalUrl\":\"https://www.playback.com/page\"}")
            .post("/api/v1/urls")
            .then()
            .statusCode(200)
            .extract()
            .path("id");

    // 2) redirect it -> redirects.total, redirect.latency, cache hits/misses, url.retrieval
    given().redirects().follow(false).when().get("/" + code).then().statusCode(302);

    // 3) SSRF block (private IP) -> security.ssrf.blocked.total
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body("{\"originalUrl\":\"http://10.0.0.1/private\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(anyOf(equalTo(400), equalTo(422)));

    // 4) expired link (see shouldCountExpiredUrls) -> urls.expired.total

    // Prometheus text format: counters render as *_total, timers as *_seconds.
    String scrape = prometheusRegistry.scrape();
    assertThat(scrape)
        .as("Prometheus scrape must export the frozen Epic 2 series (docs/slos.md §2)")
        .contains(
            "urls_shortened_total",
            "redirects_total",
            "cache_hits_total",
            "cache_misses_total",
            "bloomfilter_rejections_total",
            "id_generation_duration_seconds",
            "url_retrieval_duration_seconds",
            "shorten_latency_seconds",
            "redirect_latency_seconds",
            "urls_expired_total",
            "security_ssrf_blocked_total",
            "schema_migrations_applied_total",
            "schema_migrations_failed_total",
            "analytics_queue_depth");
  }
}
