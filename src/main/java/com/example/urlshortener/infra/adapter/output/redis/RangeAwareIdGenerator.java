package com.example.urlshortener.infra.adapter.output.redis;

import com.example.urlshortener.core.ports.outgoing.IdGeneratorPort;
import org.hashids.Hashids;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Primary // Make this the default implementation
public class RangeAwareIdGenerator implements IdGeneratorPort {

    private final StringRedisTemplate redis;
    private final Hashids hashids;
    private final AtomicLong currentId = new AtomicLong(0);
    private volatile long maxIdInCurrentRange = 0;
    private static final int RANGE_SIZE = 1000;
    private static final String SEQUENCE_KEY = "global_link_id_seq";

    private final ReentrantLock lock = new ReentrantLock();

    public RangeAwareIdGenerator(StringRedisTemplate redis, Hashids hashids) {
        this.redis = redis;
        this.hashids = hashids;
    }

    @Override
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "rateLimiterCb", fallbackMethod = "generateIdFallback")
    public String generateId() {
        long nextId = getNextUniqueId();
        return hashids.encode(nextId);
    }

    /**
     * Emergency fallback: generates IDs without Redis using timestamp + thread ID.
     * This ensures the service continues working even if Redis is down.
     */
    public String generateIdFallback(Throwable t) {
        long timestamp = System.currentTimeMillis();
        long threadId = Thread.currentThread().threadId();
        long emergencyId = (timestamp << 20) | (threadId & 0xFFFFF); // Combine timestamp + thread bits
        return hashids.encode(emergencyId);
    }

    private long getNextUniqueId() {
        long next = currentId.incrementAndGet();

        // If local range is exhausted, fetch new block from Redis
        if (next > maxIdInCurrentRange) {
            lock.lock();
            try {
                if (currentId.get() > maxIdInCurrentRange) {
                    // Call Redis INCRBY 1000
                    Long upperLimit = redis.opsForValue().increment(SEQUENCE_KEY, RANGE_SIZE);

                    if (upperLimit == null) {
                        throw new IllegalStateException("Failed to increment sequence in Redis");
                    }

                    maxIdInCurrentRange = upperLimit;
                    currentId.set(upperLimit - RANGE_SIZE + 1);
                    return currentId.get();
                }
            } finally {
                lock.unlock();
            }
            // Simple retry if another thread already updated
            return getNextUniqueId();
        }
        return next;
    }
}
