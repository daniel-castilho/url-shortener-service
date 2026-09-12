package ca.tyny.urlshortener.infra.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Custom health indicator for the URL Shortener service.
 *
 * <p>Provides detailed health information including MongoDB connectivity, Redis connectivity,
 * application uptime, and memory usage. This information is exposed via the
 * {@code /actuator/health} endpoint when details are enabled.</p>
 *
 * <p>Components checked:
 * <ul>
 *   <li><b>MongoDB</b>: Verifies database connectivity via a ping command</li>
 *   <li><b>Redis</b>: Verifies Redis connectivity via ping command</li>
 *   <li><b>Memory</b>: Reports JVM heap usage and GC activity</li>
 *   <li><b>Uptime</b>: Application uptime in seconds</li>
 * </ul>
 *
 * <p>This health indicator is automatically registered with Spring Boot Actuator
 * and contributes to the overall health status available at {@code /actuator/health}.
 *
 * @see org.springframework.boot.actuate.health.HealthIndicator
 */
@Component
public class UrlShortenerHealthIndicator implements HealthIndicator {

  private final MongoTemplate mongoTemplate;
  private final StringRedisTemplate redisTemplate;
  private final Runtime runtime = Runtime.getRuntime();
  private final long startTime = Instant.now().toEpochMilli();

  public UrlShortenerHealthIndicator(MongoTemplate mongoTemplate, StringRedisTemplate redisTemplate) {
    this.mongoTemplate = mongoTemplate;
    this.redisTemplate = redisTemplate;
  }

  @Override
  public Health health() {
    Health.Builder builder = Health.up();

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
      mongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean checkRedis() {
    try {
      return "PONG".equals(redisTemplate.getConnectionFactory().getConnection().ping());
    } catch (Exception e) {
      return false;
    }
  }

  private void addSystemDetails(Health.Builder builder) {
    Runtime rt = runtime;
    long totalMemory = rt.totalMemory();
    long freeMemory = rt.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    long maxMemory = rt.maxMemory();
    long uptimeSeconds = (Instant.now().toEpochMilli() - startTime) / 1000;

    builder.withDetail("jvm", java.lang.management.ManagementFactory.getRuntimeMXBean().getVmName())
        .withDetail("uptimeSeconds", uptimeSeconds)
        .withDetail("memory", new MemoryDetail(
            totalMemory / 1024 / 1024,
            usedMemory / 1024 / 1024,
            freeMemory / 1024 / 1024,
            maxMemory / 1024 / 1024))
        .withDetail("javaVersion", System.getProperty("java.version"))
        .withDetail("processors", Runtime.getRuntime().availableProcessors())
        .withDetail("pid", ProcessHandle.current().pid());
  }

  private record MemoryDetail(long totalMb, long usedMb, long freeMb, long maxMb) {}

  private static long startTime = java.time.Instant.now().toEpochMilli();
}