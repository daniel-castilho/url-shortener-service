package com.example.urlshortener.infra.adapter.output.redis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import java.time.Duration;

class RedisRateLimiterAdapterTest {

    private RedissonClient redissonClient;
    private RAtomicLong atomicLong;
    private RedisRateLimiterAdapter adapter;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        atomicLong = mock(RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        // Use default limit 2 and window 1 minute for test simplicity
        adapter = new RedisRateLimiterAdapter(redissonClient, 2, Duration.ofMinutes(1));
    }

    @Test
    void shouldAllowRequestsWithinLimit() {
        when(atomicLong.incrementAndGet()).thenReturn(1L, 2L);
        // first request sets expiry
        assertTrue(adapter.isAllowed("1.2.3.4"));
        // second request still within limit
        assertTrue(adapter.isAllowed("1.2.3.4"));
        verify(atomicLong, times(2)).incrementAndGet();
        verify(atomicLong, times(1)).expire(Duration.ofMinutes(1));
    }

    @Test
    void shouldBlockWhenExceedingLimit() {
        when(atomicLong.incrementAndGet()).thenReturn(1L, 2L, 3L);
        assertTrue(adapter.isAllowed("5.6.7.8")); // 1st
        assertTrue(adapter.isAllowed("5.6.7.8")); // 2nd
        assertFalse(adapter.isAllowed("5.6.7.8")); // 3rd exceeds limit of 2
    }
}
