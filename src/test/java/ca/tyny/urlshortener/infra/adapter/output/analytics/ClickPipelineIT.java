package ca.tyny.urlshortener.infra.adapter.output.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.model.ClickEvent;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.AnalyticsPort;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickEventDocument;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Click Pipeline Integration Tests")
class ClickPipelineIT extends BaseIntegrationTest {

  @Autowired private AnalyticsPort analyticsPort;

  @Autowired private UrlRepositoryPort urlRepository;

  @Autowired private ClickBatchWorker worker;

  @Autowired private MongoTemplate mongoTemplate;

  @MockitoBean private RateLimiterPort rateLimiter;

  @Test
  @DisplayName("Should persist tracked click event and increment clickCount")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldPersistEventAndIncrementCount() {
    urlRepository.save(new ShortUrl("pipe001", "https://example.com/pipe", LocalDateTime.now()));

    analyticsPort.track(
        new ClickEvent(
            "pipe001",
            LocalDateTime.now(),
            "IT-UA",
            "203.0.113.10",
            "https://ref.example.com/campaign",
            null,
            null));

    awaitAssert(
        () -> {
          ClickEventDocument doc =
              mongoTemplate.findOne(
                  Query.query(Criteria.where("shortCode").is("pipe001")), ClickEventDocument.class);
          assertThat(doc).isNotNull();
          assertThat(doc.getReferrer()).isEqualTo("https://ref.example.com/campaign");
        });
    awaitAssert(
        () ->
            assertThat(urlRepository.findById("pipe001"))
                .hasValueSatisfying(u -> assertThat(u.clickCount()).isEqualTo(1L)));
  }

  @Test
  @DisplayName("Should keep per-code counts exact across concurrent same-code events")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldKeepCountsExact() {
    urlRepository.save(new ShortUrl("pipe002", "https://example.com/multi", LocalDateTime.now()));
    List<ClickEvent> burst =
        java.util.stream.IntStream.range(0, 25)
            .mapToObj(i -> new ClickEvent("pipe002", LocalDateTime.now(), "UA", null))
            .toList();
    burst.forEach(analyticsPort::track);

    awaitAssert(
        () ->
            assertThat(urlRepository.findById("pipe002"))
                .hasValueSatisfying(u -> assertThat(u.clickCount()).isEqualTo(25L)));
    awaitAssert(
        () -> {
          long docs =
              mongoTemplate.count(
                  Query.query(Criteria.where("shortCode").is("pipe002")),
                  MongoCollections.CLICK_EVENTS);
          assertThat(docs).isEqualTo(25);
        });
  }

  @Test
  @DisplayName("Should derive device from the User-Agent in the worker (end to end)")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldDeriveDeviceEndToEnd() {
    urlRepository.save(new ShortUrl("pipe003", "https://example.com/device", LocalDateTime.now()));

    analyticsPort.track(
        new ClickEvent(
            "pipe003",
            LocalDateTime.now(),
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile/15E148 Safari/604.1",
            "203.0.113.10",
            null,
            null,
            null));

    awaitAssert(
        () -> {
          ClickEventDocument doc =
              mongoTemplate.findOne(
                  Query.query(Criteria.where("shortCode").is("pipe003")), ClickEventDocument.class);
          assertThat(doc).isNotNull();
          assertThat(doc.getDevice()).isEqualTo("mobile");
        });
  }

  @Test
  @DisplayName("Should not persist events without a short code")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldSkipBlankCodeEvents() {
    analyticsPort.track(new ClickEvent("", LocalDateTime.now(), "UA", "203.0.113.11"));
    worker.processBatch();

    // No crash and nothing persisted for the blank code
    long docs =
        mongoTemplate.count(
            Query.query(Criteria.where("shortCode").is("")), MongoCollections.CLICK_EVENTS);
    assertThat(docs).isZero();
  }

  /**
   * Polls the assertion until it holds or times out — the worker drains the stream on its own
   * schedule, so pipeline effects are eventual.
   */
  private void awaitAssert(Runnable assertion) {
    long deadline = System.currentTimeMillis() + 15_000;
    AssertionError lastFailure = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        assertion.run();
        return;
      } catch (AssertionError e) {
        lastFailure = e;
        try {
          Thread.sleep(200);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw lastFailure;
        }
      }
    }
    throw lastFailure != null
        ? lastFailure
        : new AssertionError("Condition not met within timeout");
  }
}
