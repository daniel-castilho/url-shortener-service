package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.restassured.RestAssured;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redirect path — MongoDB failure (ADR 0005 fail-closed policy).
 *
 * <p>Uses dedicated containers (not the BaseIntegrationTest singletons) because the last test stops
 * the MongoDB container mid-test: killing the shared singleton would poison every other IT in the
 * same Surefire fork. The replica set is deliberately NOT restarted inside the class —
 * Testcontainers restart of a replica set interacts badly with the app's dropped monitors; CB
 * self-healing is proven deterministically by the half-open probe test ({@code
 * circuitBreakerHalfOpenProbeCloses}) instead.
 *
 * <p>The Mongo URI pins {@code serverSelectionTimeoutMS=3000} so a dead MongoDB produces a fast,
 * bounded failure (ADR 0005 "fast failure") instead of driver-level 30s timeouts.
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "rate-limiter.enabled=true",
      "rate-limiter.redirect-limit=1000000",
      "rate-limiter.limit=1000000"
    })
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Redirect path — MongoDB outage (real container stop, fail-closed)")
class RedirectMongoFailureIT {

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
  static void props(DynamicPropertyRegistry registry) {
    String mongoUri =
        String.format(
            "mongodb://%s:%d/url_shortener?serverSelectionTimeoutMS=3000",
            mongoDB.getHost(), mongoDB.getMappedPort(27017));
    registry.add("spring.mongodb.uri", () -> mongoUri);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @LocalServerPort private int port;

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  @Autowired private ca.tyny.urlshortener.infra.adapter.output.redis.RedisUrlCache urlCache;

  private CircuitBreaker databaseCb() {
    return circuitBreakerRegistry.circuitBreaker("databaseCb");
  }

  private void configureRestAssured() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  private String shorten(String destination) {
    var request = new ShortenRequest(destination, null);
    return given()
        .contentType("application/json")
        .body(request)
        .post("/api/v1/urls")
        .then()
        .statusCode(200)
        .extract()
        .path("id");
  }

  @Test
  @Order(1)
  @DisplayName("Circuit breaker open → cold cache-miss returns 503")
  void circuitBreakerOpenColdCacheMissReturns503() {
    configureRestAssured();
    CircuitBreaker cb = databaseCb();
    cb.transitionToOpenState();
    try {
      given().redirects().follow(false).get("/9000000").then().statusCode(503);
    } finally {
      cb.transitionToClosedState();
    }
  }

  @Test
  @Order(2)
  @DisplayName("Circuit breaker closed → cold cache-miss returns 404 (not found)")
  void circuitBreakerClosedColdCacheMissReturns404() {
    configureRestAssured();
    databaseCb().transitionToClosedState();

    given().redirects().follow(false).get("/9999999").then().statusCode(404);
  }

  @Test
  @Order(3)
  @DisplayName("Circuit breaker half-open → successful probe closes the breaker (self-heal)")
  void circuitBreakerHalfOpenProbeCloses() {
    configureRestAssured();
    CircuitBreaker cb = databaseCb();
    cb.transitionToOpenState();
    cb.transitionToHalfOpenState();

    String shortId = shorten("https://example.com/probe-test");
    given().redirects().follow(false).get("/" + shortId).then().statusCode(302);

    awaitClosed(cb);
  }

  @Test
  @Order(4)
  @DisplayName(
      "Mongo down (real stop): hot code still 302 from Redis L2; cold code → 503 fail-closed")
  void mongoDownHotCodeServedColdCodeFailsClosed() {
    configureRestAssured();

    String destination = "https://example.com/hot-" + System.currentTimeMillis();
    String shortId = shorten(destination);

    // First access warms L1 (local) + L2 (Redis) + bloom.
    given().redirects().follow(false).get("/" + shortId).then().statusCode(302);

    // Real outage: stop only the MongoDB container (Redis stays up).
    mongoDB.stop();

    // Drop L1 so the lookup must consult (alive) Redis L2 — no Mongo touch for hot codes.
    urlCache.invalidateAllLocal();

    given()
        .redirects()
        .follow(false)
        .get("/" + shortId)
        .then()
        .statusCode(302)
        .header("Location", destination);

    // Cold code: bloom-negative → resolved by findById → Mongo is dead → fail-closed 503.
    given().redirects().follow(false).get("/8888888").then().statusCode(503);

    // The outage is still in effect after the assertions: dedicated container stays stopped.
    assertThat(mongoDB.isRunning()).isFalse();
  }

  private void awaitClosed(CircuitBreaker cb) {
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED));
  }
}
