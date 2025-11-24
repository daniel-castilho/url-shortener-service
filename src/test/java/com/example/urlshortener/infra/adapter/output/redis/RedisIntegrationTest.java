package com.example.urlshortener.infra.adapter.output.redis;

import org.hashids.Hashids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;

@SpringBootTest
@Testcontainers
@DisplayName("Redis Integration Tests")
class RedisIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    private RangeAwareIdGenerator idGenerator;
    private Hashids hashids;

    @BeforeEach
    void setUp() {
        hashids = new Hashids("test-salt", 7);
        idGenerator = new RangeAwareIdGenerator(redisTemplate, hashids);

        // Clean up Redis before each test
        redisTemplate.delete("global_link_id_seq");
    }

    @Test
    @DisplayName("Should generate IDs with Redis persistence")
    void shouldGenerateIdsWithRedisPersistence() {
        // When
        String id1 = idGenerator.generateId();
        String id2 = idGenerator.generateId();

        // Then
        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).hasSize(7);
        assertThat(id2).hasSize(7);
    }

    @Test
    @DisplayName("Should batch IDs locally and minimize Redis calls")
    void shouldBatchIdsLocally() {
        // Given
        Long initialValue = redisTemplate.opsForValue().get("global_link_id_seq") != null
                ? Long.parseLong(redisTemplate.opsForValue().get("global_link_id_seq"))
                : 0L;

        // When: Generate 5 IDs
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ids.add(idGenerator.generateId());
        }

        // Then: All IDs should be unique
        assertThat(ids).hasSize(5);

        // And: Redis should have been incremented by 1000 (one batch)
        Long finalValue = Long.parseLong(redisTemplate.opsForValue().get("global_link_id_seq"));
        assertThat(finalValue - initialValue).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Should generate 10,000 unique IDs")
    void shouldGenerateManyUniqueIds() {
        // When
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(idGenerator.generateId());
        }

        // Then
        assertThat(ids).hasSize(10_000);
    }

    @Test
    @DisplayName("Should persist sequence in Redis")
    void shouldPersistSequenceInRedis() {
        // When
        idGenerator.generateId();

        // Then
        String sequence = redisTemplate.opsForValue().get("global_link_id_seq");
        assertThat(sequence).isNotNull();
        assertThat(Long.parseLong(sequence)).isGreaterThan(0);
    }
}
