package ca.tyny.urlshortener.infra.config;

import java.time.Instant;
import org.bson.Document;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator for the URL Shortener service.
 *
 * <p>Provides detailed health information including MongoDB connectivity, Redis connectivity,
 * application uptime, and memory usage. This information is exposed via the {@code
 * /actuator/health} endpoint when details are enabled.
 *
 * <p>Components checked:
 *
 * <ul>
 *   <li><b>MongoDB</b>: Verifies database connectivity via a ping command
 *   <li><b>Redis</b>: Verifies Redis connectivity via ping command
 *   <li><b>Memory</b>: Reports JVM heap usage and GC activity
 *   <li><b>Uptime</b>: Application uptime in seconds
 * </ul>
 *
 * <p>This health indicator is automatically registered with Spring Boot Actuator and contributes to
 * the overall health status available at {@code /actuator/health}.
 *
 * @see org.springframework.boot.health.contributor.HealthIndicator
 */
@Component
public class UrlShortenerHealthIndicator implements HealthIndicator {

  private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
  private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
  private final Runtime runtime = Runtime.getRuntime();
  private final long startTime = Instant.now().toEpochMilli();

  public UrlShortenerHealthIndicator(
      org.springframework.data.mongodb.core.MongoTemplate mongoTemplate,
      org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
    this.mongoTemplate = mongoTemplate;
    this.redisTemplate = redisTemplate;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    var builder = org.springframework.boot.health.contributor.Health.up();

    // Check MongoDB
    boolean mongoUp = checkMongo();
    builder.withDetail("mongo", mongoUp ? "UP" : "DOWN");
    if (!mongoUp) {
      return builder.down().build();
    }

    // Check Redis
    boolean redisUp = checkRedis();
    builder.withDetail("redis", redisUp ? "UP" : "DOWN");
    if (!redisUp) {
      return builder.down().build();
    }

    // Add system details
    addSystemDetails(builder);

    return builder.up().build();
  }

  private boolean checkMongo() {
    try {
      mongoTemplate.getDb().runCommand(new Document("ping", 1));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean checkRedis() {
    // try-with-resources: the borrowed connection must be returned on every probe, or each
    // health call leaks one RedisConnection and exhausts the pool under continuous probing.
    try (org.springframework.data.redis.connection.RedisConnection connection =
        redisTemplate.getConnectionFactory().getConnection()) {
      return "PONG".equals(connection.ping());
    } catch (Exception e) {
      return false;
    }
  }

  private void addSystemDetails(
      org.springframework.boot.health.contributor.Health.Builder builder) {
    Runtime rt = runtime;
    long totalMemory = rt.totalMemory();
    long freeMemory = rt.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    long maxMemory = rt.maxMemory();
    long uptimeSeconds = (Instant.now().toEpochMilli() - startTime) / 1000;

    builder
        .withDetail("jvm", java.lang.management.ManagementFactory.getRuntimeMXBean().getVmName())
        .withDetail("uptimeSeconds", uptimeSeconds)
        .withDetail(
            "memory",
            new MemoryDetail(
                totalMemory / 1024 / 1024,
                usedMemory / 1024 / 1024,
                freeMemory / 1024 / 1024,
                maxMemory / 1024 / 1024))
        .withDetail("javaVersion", System.getProperty("java.version"))
        .withDetail("processors", Runtime.getRuntime().availableProcessors())
        .withDetail("pid", ProcessHandle.current().pid());
  }

  private record MemoryDetail(long totalMb, long usedMb, long freeMb, long maxMb) {}
}
