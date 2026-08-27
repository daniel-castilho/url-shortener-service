package ca.tyny.urlshortener.infra.adapter.output.analytics;

import ca.tyny.urlshortener.core.model.ClickEvent;
import ca.tyny.urlshortener.core.ports.outgoing.AnalyticsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Durable click-event queue backed by a Redis Stream.
 *
 * Replaces the in-memory LinkedBlockingQueue: events survive restarts and
 * bursts, and the stream is bounded via approximate MAXLEN trimming.
 *
 * Policy (locked): fire-and-forget + fail-open. track() never blocks the
 * redirect and never throws — on Redis failure the event is dropped and
 * counted (analytics is non-critical path).
 */
@Component
public class RedisClickEventQueue implements AnalyticsPort {

    private static final Logger log = LoggerFactory.getLogger(RedisClickEventQueue.class);

    public static final String FIELD_CODE = "code";
    public static final String FIELD_TIMESTAMP = "ts";
    public static final String FIELD_USER_AGENT = "ua";
    public static final String FIELD_IP = "ip";

    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final long streamMaxlen;
    private final Counter enqueuedCounter;
    private final Counter droppedCounter;

    public RedisClickEventQueue(StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${app.analytics.stream-key:urlshortener:clicks}") String streamKey,
            @Value("${app.analytics.stream-maxlen:1000000}") long streamMaxlen) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.streamMaxlen = streamMaxlen;
        this.enqueuedCounter = Counter.builder("analytics.events.enqueued.total")
                .description("Click events durably enqueued onto the Redis Stream")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
        this.droppedCounter = Counter.builder("analytics.events.dropped.total")
                .description("Click events dropped (Redis unavailable or stream write failed)")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
        Gauge.builder("analytics.queue.depth",
                        this,
                        RedisClickEventQueue::streamLength)
                .description("Approximate number of click events pending in the Redis Stream (XLEN)")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
    }

    /**
     * Current stream depth for the metrics gauge. Fail-open: reports 0 when
     * Redis is unavailable so scraping never breaks.
     */
    long streamLength() {
        try {
            Long len = redisTemplate.opsForStream().size(streamKey);
            return len != null ? len : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public void track(ClickEvent event) {
        try {
            Map<String, String> payload = toPayload(event);
            redisTemplate.opsForStream().add(
                    Record.of(payload).withStreamKey(streamKey),
                    XAddOptions.maxlen(streamMaxlen).approximateTrimming(true));
            enqueuedCounter.increment();
        } catch (Exception e) {
            // Fail-open: analytics must never break the redirect path
            droppedCounter.increment();
            log.warn("Failed to enqueue click event for code {} — event dropped", event.shortCode(), e);
        }
    }

    /**
     * Maps a domain event to its stream payload. Timestamps are normalized to
     * UTC instants at this boundary; the domain keeps LocalDateTime.
     */
    static Map<String, String> toPayload(ClickEvent event) {
        Instant instant = event.timestamp() != null
                ? event.timestamp().atZone(ZoneOffset.UTC).toInstant()
                : Instant.now();
        Map<String, String> payload = new HashMap<>();
        payload.put(FIELD_CODE, event.shortCode());
        payload.put(FIELD_TIMESTAMP, instant.toString());
        payload.put(FIELD_USER_AGENT, event.userAgent());
        payload.put(FIELD_IP, event.ip());
        return payload;
    }
}
