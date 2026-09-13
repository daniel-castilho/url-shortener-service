package ca.tyny.urlshortener.infra.adapter.output.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.model.ClickEvent;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.AnalyticsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Analytics pipeline — at-least-once redelivery from the PEL (Epic 7 story 7.4).
 *
 * <p>Regression guard for a REAL at-least-once gap: {@link ClickBatchWorker} now drains the
 * consumer group's Pending Entries List with XREADGROUP offset 0 BEFORE reading new messages with
 * ">" (the Redis-documented crash-recovery pattern). Previously only ">" was read, so a batch
 * rejected by MongoDB stayed pending forever — the documented redelivery and the 3-failure finalize
 * were both dead code.
 *
 * <p>Deterministic isolation: dedicated Mongo + Redis containers and a poll interval of 1h, so
 * the @Scheduled worker never races the manual {@code worker.processBatch()} calls below. The
 * MongoDB {@code insertAll} failure is injected deterministically by opening the shared {@code
 * databaseCb} circuit breaker (the annotated adapter throws before touching Mongo), and recovered
 * by closing it — no container stop/restart needed.
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.analytics.poll-interval-ms=3600000",
      "app.analytics.stream-key=it:clicks",
      "app.analytics.group=it-click-worker",
      "app.analytics.consumer=it-worker-1",
      "rate-limiter.enabled=false"
    })
@Testcontainers
@DisplayName("Analytics pipeline — at-least-once PEL redelivery (Epic 7 7.4)")
class ClickPipelineRedeliveryIT {

  @Container
  static final MongoDBContainer mongoDB =
      new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
          .withExposedPorts(27017)
          .withReplicaSet()
          .withCreateContainerCmdModifier(
              cmd -> {
                cmd.withUlimits(
                    new com.github.dockerjava.api.model.Ulimit("nofile", 65536L, 65536L));
                String[] base = cmd.getCmd();
                java.util.List<String> full = new java.util.ArrayList<>();
                if (base != null) {
                  full.addAll(java.util.Arrays.asList(base));
                }
                full.add("--wiredTigerCacheSizeGB=0.25");
                cmd.withCmd(full.toArray(new String[0]));
              });

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.1"))
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(
              cmd ->
                  cmd.withUlimits(
                      new com.github.dockerjava.api.model.Ulimit("nofile", 65536L, 65536L)));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    String mongoUri =
        String.format(
            "mongodb://%s:%d/url_shortener", mongoDB.getHost(), mongoDB.getMappedPort(27017));
    registry.add("spring.mongodb.uri", () -> mongoUri);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired private AnalyticsPort analyticsPort;
  @Autowired private UrlRepositoryPort urlRepository;
  @Autowired private ClickBatchWorker worker;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
  @Autowired private MeterRegistry meterRegistry;

  @Value("${app.analytics.stream-key}")
  private String streamKey;

  @Value("${app.analytics.group}")
  private String groupName;

  @BeforeEach
  void reset() {
    mongoTemplate.getDb().drop();
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  @DisplayName(
      "Failed batch is reclaimed from the PEL and persisted after recovery (at-least-once)")
  @TracesRequirement("REQ-ANALYTICS-003")
  void failedBatchIsReclaimedAfterRecovery() {
    String code = "pel001";
    urlRepository.save(new ShortUrl(code, "https://example.com/al", LocalDateTime.now()));
    int n = 5;
    IntStream.range(0, n)
        .forEach(
            i ->
                analyticsPort.track(
                    new ClickEvent(code, LocalDateTime.now(), "UA", "203.0.113.10")));

    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("databaseCb");
    cb.transitionToOpenState();
    try {
      // First tick: new messages are read with ">", insertion fails, batch stays un-acked in PEL.
      double failedBefore = failedEventsMetric();
      worker.processBatch();
      assertThat(pendingCount()).isEqualTo(n);
      assertThat(clickEventCount(code)).isZero();
      // clickCount untouched while the CB is open: persistBatch fails at insertAll, before the
      // atomic $inc per unique code (findById cannot be asserted here — it is also CB-wrapped).
      assertThat(failedEventsMetric() - failedBefore).isEqualTo(n);
    } finally {
      cb.transitionToClosedState();
    }

    // Recovery "restart": the next tick reclaims the PEL (offset 0) and persists + acks.
    worker.processBatch();

    assertThat(pendingCount()).isZero();
    assertThat(clickEventCount(code)).isEqualTo(n);
    assertThat(urlRepository.findById(code))
        .get()
        .satisfies(u -> assertThat(u.clickCount()).isEqualTo(n));
  }

  @Test
  @DisplayName("Poison batch is finalized after 3 consecutive failures; subsequent events persist")
  @TracesRequirement("REQ-ANALYTICS-003")
  void poisonBatchIsFinalizedAndGroupKeepsProcessing() {
    String poisonCode = "pel002";
    String freshCode = "pel003";
    urlRepository.save(new ShortUrl(poisonCode, "https://example.com/poison", LocalDateTime.now()));
    urlRepository.save(new ShortUrl(freshCode, "https://example.com/fresh", LocalDateTime.now()));

    // The "poison": a batch that cannot be persisted. Inject events while persist must fail.
    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("databaseCb");
    cb.transitionToOpenState();
    try {
      double failedBefore = failedEventsMetric();
      for (int i = 0; i < 2; i++) {
        analyticsPort.track(new ClickEvent(poisonCode, LocalDateTime.now(), "UA", "203.0.113.11"));
      }
      worker.processBatch(); // attempt 1 — fails, stays pending
      assertThat(pendingCount()).isEqualTo(2);
      worker.processBatch(); // attempt 2 — reclaimed, fails again (consecutive=2)
      assertThat(pendingCount()).isEqualTo(2);
      worker.processBatch(); // attempt 3 — reclaimed, fails (consecutive=3) → finalized (acked)
      assertThat(pendingCount()).isZero();
      assertThat(clickEventCount(poisonCode)).isZero();
      assertThat(failedEventsMetric() - failedBefore).isEqualTo(6); // 3 attempts x 2 events
    } finally {
      cb.transitionToClosedState();
    }

    // Group is not wedged: events enqueued after the poison persist normally.
    analyticsPort.track(new ClickEvent(freshCode, LocalDateTime.now(), "UA", "203.0.113.12"));
    worker.processBatch();
    assertThat(clickEventCount(freshCode)).isEqualTo(1);
    assertThat(urlRepository.findById(freshCode))
        .get()
        .satisfies(u -> assertThat(u.clickCount()).isEqualTo(1L));
  }

  private long pendingCount() {
    return redisTemplate.opsForStream().pending(streamKey, groupName).getTotalPendingMessages();
  }

  private long clickEventCount(String shortCode) {
    return mongoTemplate.count(
        Query.query(Criteria.where("shortCode").is(shortCode)), MongoCollections.CLICK_EVENTS);
  }

  private double failedEventsMetric() {
    return meterRegistry
        .get("analytics.events.failed.total")
        .tag("pipeline", "clicks")
        .counter()
        .count();
  }
}
