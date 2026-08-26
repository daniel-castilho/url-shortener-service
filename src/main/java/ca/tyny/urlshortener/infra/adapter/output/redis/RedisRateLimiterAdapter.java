package ca.tyny.urlshortener.infra.adapter.output.redis;

import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimitScope;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.infra.config.properties.RateLimiterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis token-bucket rate limiter (Rule 5) — best practices merged from the
 * flowtxt and spotpobre limiters:
 *
 * <ul>
 * <li><b>Token bucket</b> per (scope, IP): capacity = limit, continuous refill
 * at {@code limit/window} tokens per second — allows short bursts, smooth
 * sustained rates.</li>
 * <li><b>Redis TIME</b> drives refill inside one atomic Lua script, so all app
 * instances share a single clock and N racing instances can never over-admit.
 * No application clock is read.</li>
 * <li>State is a small hash ({@code tokens, ts}); TTL is
 * {@code max(60s, 2 × full-refill period)} so idle buckets self-expire — no
 * unbounded key growth under IP rotation.</li>
 * <li><b>Fail-open</b>: on any Redis failure or unexpected reply the request
 * is allowed and logged. Throttling must never take down the endpoint it
 * protects; when {@code rate-limiter.enabled=false} nothing touches Redis.</li>
 * </ul>
 *
 * Raw IPs are stored in bucket keys (deliberate deviation from spotpobre's
 * HMAC-encoded subjects: on-prem single-tenant Redis, keys are already TTL'd).
 */
@Component
public class RedisRateLimiterAdapter implements RateLimiterPort {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterAdapter.class);

    /**
     * KEYS[1] = bucket key; ARGV: capacity, refillPerSecond.
     * Returns {allowed(0|1), remainingWholeTokens, resetSeconds}.
     */
    static final RedisScript<List> TOKEN_BUCKET = buildScript();

    @SuppressWarnings("unchecked")
    private static RedisScript<List> buildScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText("""
                local t = redis.call('TIME')
                local now = tonumber(t[1]) + tonumber(t[2]) / 1000000
                local capacity = tonumber(ARGV[1])
                local refillPerSecond = tonumber(ARGV[2])

                local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
                local ts = tonumber(redis.call('HGET', KEYS[1], 'ts'))
                if tokens == nil then tokens = capacity end
                if ts == nil then ts = now end

                local elapsed = math.max(0, now - ts)
                tokens = math.min(capacity, tokens + elapsed * refillPerSecond)

                local allowed = 0
                if tokens >= 1 then
                    tokens = tokens - 1
                    allowed = 1
                end

                local resetSeconds = 0
                if allowed == 0 then
                    resetSeconds = math.ceil((1 - tokens) / math.max(refillPerSecond, 0.000001))
                    if resetSeconds < 1 then resetSeconds = 1 end
                end

                redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
                local ttl = math.max(60, math.ceil(capacity / math.max(refillPerSecond, 0.000001)) * 2)
                redis.call('EXPIRE', KEYS[1], ttl)
                return {allowed, math.floor(tokens), resetSeconds}
                """);
        script.setResultType((Class<List>) (Class<?>) List.class);
        return script;
    }

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterProperties properties;

    public RedisRateLimiterAdapter(StringRedisTemplate redisTemplate, RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(
            name = "rateLimiterCb", fallbackMethod = "allowRequestOnFailure")
    public RateLimitVerdict tryAcquire(RateLimitScope scope, String ip) {
        if (!properties.enabled()) {
            return RateLimitVerdict.allow(Long.MAX_VALUE);
        }
        long limit = scope == RateLimitScope.REDIRECT
                ? properties.redirectLimit()
                : properties.limit();
        var window = scope == RateLimitScope.REDIRECT
                ? properties.redirectWindow()
                : properties.window();
        double refillPerSecond = limit / Math.max(1d, window.toMillis() / 1000d);

        List<?> reply = redisTemplate.execute(
                TOKEN_BUCKET,
                List.of(bucketKey(scope, ip)),
                String.valueOf(limit),
                String.valueOf(refillPerSecond));

        if (reply == null || reply.size() < 3) {
            // Fail-open on unexpected shape too — same policy as an outage
            log.warn("Rate limiter got an unexpected Redis reply for {}:{}; allowing", scope, ip);
            return RateLimitVerdict.allow(Long.MAX_VALUE);
        }
        long allowed = ((Number) reply.get(0)).longValue();
        long remaining = ((Number) reply.get(1)).longValue();
        long resetSeconds = ((Number) reply.get(2)).longValue();
        return allowed == 1 ? RateLimitVerdict.allow(remaining) : RateLimitVerdict.block(resetSeconds);
    }

    /** Bucket keys are structurally distinct per scope — no cross-exhaustion. */
    static String bucketKey(RateLimitScope scope, String ip) {
        return "rl:" + scope.name().toLowerCase() + ":" + ip;
    }

    public RateLimitVerdict allowRequestOnFailure(RateLimitScope scope, String ip, Throwable t) {
        log.warn("Rate limiter unavailable ({}:{}), failing open: {}", scope, ip, t.getMessage());
        return RateLimitVerdict.allow(Long.MAX_VALUE);
    }
}
