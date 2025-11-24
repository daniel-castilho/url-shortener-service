package com.example.urlshortener.infra.adapter.output.redis;

import org.hashids.Hashids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RangeAwareIdGenerator Tests")
class RangeAwareIdGeneratorTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private Hashids hashids;
    private RangeAwareIdGenerator generator;

    @BeforeEach
    void setUp() {
        hashids = new Hashids("test-salt", 7);
        when(redis.opsForValue()).thenReturn(valueOperations);
        generator = new RangeAwareIdGenerator(redis, hashids);
    }

    @Test
    @DisplayName("Should generate first ID and fetch range from Redis")
    void shouldGenerateFirstId() {
        // Given
        when(valueOperations.increment(eq("global_link_id_seq"), eq(1000L)))
                .thenReturn(1000L);

        // When
        String id = generator.generateId();

        // Then
        assertThat(id).isNotNull();
        assertThat(id).hasSize(7); // Minimum length from Hashids
        verify(valueOperations).increment("global_link_id_seq", 1000L);
    }

    @Test
    @DisplayName("Should generate multiple IDs without calling Redis (batching)")
    void shouldBatchIdsLocally() {
        // Given
        when(valueOperations.increment(eq("global_link_id_seq"), eq(1000L)))
                .thenReturn(1000L);

        // When
        String id1 = generator.generateId();
        String id2 = generator.generateId();
        String id3 = generator.generateId();

        // Then
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id2).isNotEqualTo(id3);
        assertThat(id1).isNotEqualTo(id3);

        // Redis should only be called once for the range
        verify(valueOperations, times(1)).increment(anyString(), anyLong());
    }

    @Test
    @DisplayName("Should generate unique IDs")
    void shouldGenerateUniqueIds() {
        // Given
        when(valueOperations.increment(eq("global_link_id_seq"), eq(1000L)))
                .thenReturn(1000L)
                .thenReturn(2000L);

        // When
        String id1 = generator.generateId();
        String id2 = generator.generateId();

        // Then
        assertThat(id1).isNotEqualTo(id2);
    }
}
