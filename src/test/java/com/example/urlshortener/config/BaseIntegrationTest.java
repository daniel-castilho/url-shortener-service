package com.example.urlshortener.config;

import com.example.urlshortener.Application;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public abstract class BaseIntegrationTest {

        static final MongoDBContainer mongoDB = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
                        .withExposedPorts(27017);

        static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:alpine"))
                        .withExposedPorts(6379);

        static {
                mongoDB.start();
                redis.start();
        }

        @org.springframework.beans.factory.annotation.Autowired
        private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

        @org.springframework.beans.factory.annotation.Autowired
        private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

        @org.springframework.beans.factory.annotation.Autowired
        private com.example.urlshortener.infra.adapter.output.redis.RedisUrlCache redisUrlCache;

        @org.junit.jupiter.api.BeforeEach
        @org.junit.jupiter.api.AfterEach
        void cleanup() {
                mongoTemplate.getDb().drop();
                redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
                if (redisUrlCache != null) {
                        redisUrlCache.resetBloomFilter();
                }
        }

        @DynamicPropertySource
        static void registerProperties(DynamicPropertyRegistry registry) {
                // MongoDB
                String mongoUri = String.format("mongodb://%s:%d/url_shortener",
                                mongoDB.getHost(), mongoDB.getMappedPort(27017));
                registry.add("spring.data.mongodb.uri", () -> mongoUri);

                // Redis
                registry.add("spring.data.redis.host", redis::getHost);
                registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        }
}
