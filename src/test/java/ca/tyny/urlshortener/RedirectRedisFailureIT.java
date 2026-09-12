package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Redirect path — real Redis outage (ADR 0005 degrade policy).
 *
 * <p>Uses dedicated containers (not the BaseIntegrationTest singletons) because this class stops
 * Redis mid-test: killing the shared replay would poison every other IT in the same Surefire fork.
 * The Mongo replica set is dedicated too so the redirect can still resolve codes through Mongo
 * after Redis is stopped.
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "rate-limiter.enabled=true",
      "rate-limiter.redirect-limit=3",
      "rate-limiter.limit=1000000"
    })
@Testcontainers
@DisplayName("Redirect path — Redis outage (cache + rate-limit fail-open/degrade)")
class RedirectRedisFailureIT {

  @Container
  private static final MongoDBContainer mongoDB =
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
  private static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.1"))
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(
              cmd -> {
                cmd.withUlimits(
                    new com.github.dockerjava.api.model.Ulimit("nofile", 65536L, 65536L));
              });

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    String mongoUri =
        String.format(
            "mongodb://%s:%d/url_shortener", mongoDB.getHost(), mongoDB.getMappedPort(27017));
    registry.add("spring.mongodb.uri", () -> mongoUri);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @LocalServerPort private int port;

  @Autowired private ca.tyny.urlshortener.infra.adapter.output.redis.RedisUrlCache urlCache;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @AfterEach
  void tearDown() {
    // Ensure a stopped Redis is restarted for the next test in this class.
    if (!redis.isRunning()) {
      redis.start();
    }
  }

  @Test
  @DisplayName("Redis down on cache miss → degrade → resolves via Mongo (302)")
  void redisDownCacheMissFallsBackToMongo() {
    String destination = "https://example.com/fallback-" + System.currentTimeMillis();
    var request =
        new ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest(destination, null);
    String shortId =
        given()
            .contentType("application/json")
            .body(request)
            .post("/api/v1/urls")
            .then()
            .statusCode(200)
            .extract()
            .path("id");

    // First access: populate L1 + L2 (Redis alive).
    given().redirects().follow(false).get("/" + shortId).then().statusCode(302);

    // Kill Redis mid-test.
    redis.stop();

    // Clear L1 so the next lookup must consult (dead) Redis, then degrade to Mongo.
    urlCache.invalidateAllLocal();

    given()
        .redirects()
        .follow(false)
        .get("/" + shortId)
        .then()
        .statusCode(302)
        .header("Location", destination);
  }

  @Test
  @DisplayName("Redis down → rate limiter fails open (requests beyond the 3-token limit still 302)")
  void redisDownRateLimiterFailsOpen() {
    String destination = "https://example.com/rl-" + System.currentTimeMillis();
    var request =
        new ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest(destination, null);
    String shortId =
        given()
            .contentType("application/json")
            .body(request)
            .post("/api/v1/urls")
            .then()
            .statusCode(200)
            .extract()
            .path("id");

    // Populate L1/L2 while Redis is still up so the outage below stresses only the
    // rate limiter (the anti-enumeration control on the hot path).
    given().redirects().follow(false).get("/" + shortId).then().statusCode(302);

    redis.stop();

    // With the limiter UP, redirect-limit=3 would 429 the 4th+ request from the same IP.
    // With Redis DOWN the limiter fails OPEN (ADR 0005): every request is served with 302.
    for (int i = 0; i < 6; i++) {
      given().redirects().follow(false).get("/" + shortId).then().statusCode(302);
    }

    // The anti-enumeration control is DOWN (fail-open) → all requests served, none 429/5xx.
    assertThat(redis.isRunning()).isFalse();
  }
}
