package com.example.urlshortener.infra.adapter.output.redis;

import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis based token‑bucket rate limiter. Uses a simple counter per IP address
 * with a TTL equal to the window size. The limit and window are configurable
 * via application.yml (properties `rate-limiter.limit` and
 * `rate-limiter.window`).
 */
@Component
public class RedisRateLimiterAdapter implements RateLimiterPort {
    private final long limit;
    private final Duration window;
    private final RedissonClient redisson;

    public RedisRateLimiterAdapter(
            RedissonClient redisson,
            @Value("${rate-limiter.limit:60}") long limit,
            @Value("${rate-limiter.window:PT1M}") Duration window) {
        this.redisson = redisson;
        this.limit = limit;
        this.window = window;
    }

    @Override
    public boolean isAllowed(String ip) {
        String key = "rl:" + ip;
        RAtomicLong counter = redisson.getAtomicLong(key);
        long current = counter.incrementAndGet();
        if (current == 1) {
            // first request for this window – set expiry
            counter.expire(window);
        }
        return current <= limit;
    }
}
