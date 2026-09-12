package ca.tyny.urlshortener.config;

import ca.tyny.urlshortener.Application;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton-container integration base (dargent lessons.md #7 pattern).
 *
 * <p>No @DirtiesContext: it re-created the whole ApplicationContext per test method (114 contexts
 * for 114 tests), each opening its own Mongo connection pool and Redisson client. The shared mongod
 * accumulated those connections/threads/file descriptors until it hit the EMFILE limit mid-suite —
 * WiredTiger's "Too many open files" directory-sync panic aborts mongod (exit 14) and every test
 * after that point fails with "Prematurely reached end of stream". Test isolation is provided by
 * the @BeforeEach/@AfterEach cleanup (drop database, Redis flushAll, Bloom + L1 cache reset), not
 * by context re-creation.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

  public static final MongoDBContainer mongoDB =
      new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
          .withExposedPorts(27017)
          // Testcontainers 2.x: rs.initiate() only runs when withReplicaSet() is used
          // (the 1.x MongoDBContainer did it automatically). The app uses transactions,
          // so a primary is mandatory — NotPrimaryOrSecondary otherwise.
          .withReplicaSet()
          // Keep the test mongod small on the shared Docker Desktop/WSL2 VM:
          // WiredTiger defaults its cache to ~50% of the VM's RAM.
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

  public static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:alpine"))
          .withExposedPorts(6379)
          .withHealthcheck(
              org.testcontainers.containers.wait.strategy.HostPortWaitStrategy.forPort(6379)
                  .withStartupTimeout(java.time.Duration.ofSeconds(30)));

  static {
    mongoDB.start();
    redis.start();
  }

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

  @org.springframework.beans.factory.annotation.Autowired
  private ca.tyny.urlshortener.infra.adapter.output.redis.RedisUrlCache redisUrlCache;

  @org.junit.jupiter.api.BeforeEach
  @org.junit.jupiter.api.AfterEach
  void cleanup() {
    mongoTemplate.getDb().drop();
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    if (redisUrlCache != null) {
      redisUrlCache.resetBloomFilter();
      redisUrlCache.invalidateAllLocal();
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    // MongoDB
    String mongoUri =
        String.format(
            "mongodb://%s:%d/url_shortener", mongoDB.getHost(), mongoDB.getMappedPort(27017));
    registry.add("spring.mongodb.uri", () -> mongoUri);

    // Redis
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }
}
