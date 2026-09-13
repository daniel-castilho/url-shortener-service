package ca.tyny.urlshortener.infra.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for {@link UrlShortenerHealthIndicator}.
 *
 * <p>Covers the component contract (mongo-before-redis short-circuit, PONG semantics, system
 * details) and the connection-leak regression: {@code checkRedis()} must close the borrowed {@link
 * RedisConnection} on every probe — one leaked connection per health call would exhaust the pool
 * under continuous actuator probing.
 */
@ExtendWith(MockitoExtension.class)
class UrlShortenerHealthIndicatorTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private com.mongodb.client.MongoDatabase mongoDatabase;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private RedisConnectionFactory connectionFactory;
  @Mock private RedisConnection connection;

  private UrlShortenerHealthIndicator indicator;

  @BeforeEach
  void setUp() {
    indicator = new UrlShortenerHealthIndicator(mongoTemplate, redisTemplate);
  }

  private void stubMongoUp() {
    lenient().when(mongoTemplate.getDb()).thenReturn(mongoDatabase);
    lenient()
        .when(mongoDatabase.runCommand(new Document("ping", 1)))
        .thenReturn(new Document("ok", 1.0));
  }

  private void stubRedisUp() {
    lenient().when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
    lenient().when(connectionFactory.getConnection()).thenReturn(connection);
    lenient().when(connection.ping()).thenReturn("PONG");
  }

  @Test
  @DisplayName("both backends up -> UP with mongo/redis/uptime/memory/javaVersion/pid details")
  void bothUpReportsUpWithDetails() {
    stubMongoUp();
    stubRedisUp();

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Health.up().build().getStatus());
    assertThat(health.getDetails())
        .containsEntry("mongo", "UP")
        .containsEntry("redis", "UP")
        .containsKeys("uptimeSeconds", "memory", "javaVersion", "pid");
    assertThat(health.getDetails().get("uptimeSeconds")).isInstanceOf(Long.class);
  }

  @Test
  @DisplayName("mongo down -> DOWN and short-circuit: redis is never checked")
  void mongoDownShortCircuitsBeforeRedis() {
    when(mongoTemplate.getDb()).thenReturn(mongoDatabase);
    when(mongoDatabase.runCommand(new Document("ping", 1)))
        .thenThrow(new IllegalStateException("mongo unreachable"));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Health.down().build().getStatus());
    assertThat(health.getDetails()).containsEntry("mongo", "DOWN");
    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("redis ping throws -> DOWN with redis=DOWN")
  void redisExceptionReportsDown() {
    stubMongoUp();
    when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
    when(connectionFactory.getConnection()).thenReturn(connection);
    when(connection.ping()).thenThrow(new IllegalStateException("redis unreachable"));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Health.down().build().getStatus());
    assertThat(health.getDetails()).containsEntry("mongo", "UP").containsEntry("redis", "DOWN");
  }

  @Test
  @DisplayName("redis ping returns non-PONG -> DOWN with redis=DOWN")
  void redisNonPongReportsDown() {
    stubMongoUp();
    when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
    when(connectionFactory.getConnection()).thenReturn(connection);
    when(connection.ping()).thenReturn("NOPE");

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Health.down().build().getStatus());
    assertThat(health.getDetails()).containsEntry("mongo", "UP").containsEntry("redis", "DOWN");
  }

  /**
   * Regression test for the Redis connection leak: {@code checkRedis()} used to borrow a connection
   * via {@code getConnectionFactory().getConnection()} and never close it — one leaked {@link
   * RedisConnection} per health probe. This test FAILS before the try-with-resources fix ({@code
   * close()} never invoked) and passes after it.
   */
  @Test
  @DisplayName("no connection leak: every health() call closes the borrowed Redis connection")
  void healthDoesNotLeakRedisConnection() {
    stubMongoUp();
    stubRedisUp();

    int calls = 3;
    for (int i = 0; i < calls; i++) {
      assertThat(indicator.health().getStatus()).isEqualTo(Health.up().build().getStatus());
    }

    verify(connection, times(calls)).close();
    verify(connectionFactory, times(calls)).getConnection();
  }
}
