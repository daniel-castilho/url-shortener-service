package ca.tyny.urlshortener.infra.adapter.output.analytics;

import ca.tyny.urlshortener.infra.adapter.output.persistence.MongoClickEventRepository;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickEventDocument;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes click events from the durable Redis Stream and makes them real:
 * bulk-inserts {@link ClickEventDocument} rows into the {@code click_events}
 * collection and atomically increments ({@code $inc}) the per-link
 * {@code clickCount} once per unique code in the batch, then acknowledges.
 *
 * Delivery is at-least-once WITHOUT an idempotency key (locked decision):
 * a retried batch may duplicate a rare event row, but {@code clickCount}
 * stays exact because each batch increments per unique code only after its
 * events are persisted. On Mongo failure the batch is not acknowledged and
 * retried on subsequent ticks; after {@value #MAX_CONSECUTIVE_FAILURES}
 * consecutive failures the batch is finalized (acked) with a failed metric so
 * a prolonged outage cannot wedge the pipeline. The worker never throws —
 * an analytics failure must never crash the application.
 */
@Component
public class ClickBatchWorker {

    private static final Logger log = LoggerFactory.getLogger(ClickBatchWorker.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final StringRedisTemplate redisTemplate;
    private final MongoClickEventRepository clickEventRepository;
    private final ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort urlRepository;
    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final int batchSize;
    private final Counter persistedCounter;
    private final Counter failedCounter;

    private int consecutiveFailures = 0;

    public ClickBatchWorker(StringRedisTemplate redisTemplate,
            MongoClickEventRepository clickEventRepository,
            ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort urlRepository,
            MeterRegistry meterRegistry,
            @Value("${app.analytics.stream-key:urlshortener:clicks}") String streamKey,
            @Value("${app.analytics.group:click-worker}") String groupName,
            @Value("${app.analytics.consumer:worker-1}") String consumerName,
            @Value("${app.analytics.batch-size:500}") int batchSize) {
        this.redisTemplate = redisTemplate;
        this.clickEventRepository = clickEventRepository;
        this.urlRepository = urlRepository;
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.batchSize = batchSize;
        this.persistedCounter = Counter.builder("analytics.events.persisted.total")
                .description("Click events persisted to the click_events collection")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("analytics.events.failed.total")
                .description("Click events whose batch processing failed")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
    }

    /**
     * Ensures the consumer group exists (idempotent). Called eagerly so the
     * first scheduled tick does not race group creation. XGROUP CREATE requires
     * the stream key to exist, so a bootstrap record is added when needed and
     * removed afterwards; the group reads from offset 0 so events enqueued
     * before startup are consumed too.
     */
    @jakarta.annotation.PostConstruct
    void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
            log.info("Created Redis Stream consumer group '{}' on '{}'", groupName, streamKey);
        } catch (Exception groupExistsOrNoStream) {
            if (!bootstrapGroupOnMissingStream()) {
                log.debug("Consumer group '{}' already exists or stream unavailable: {}",
                        groupName, groupExistsOrNoStream.getMessage());
            }
        }
    }

    private boolean bootstrapGroupOnMissingStream() {
        try {
            // XGROUP CREATE requires the stream key to exist: add a temporary
            // bootstrap record, create the group, then delete just that record.
            // Safe: this branch only runs when the stream does not exist yet.
            org.springframework.data.redis.connection.stream.RecordId bootstrapId = redisTemplate
                    .opsForStream()
                    .add(streamKey, java.util.Map.of("bootstrap", "1"));
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
            redisTemplate.opsForStream().delete(streamKey, bootstrapId);
            log.info("Created Redis Stream consumer group '{}' on '{}' (stream bootstrapped)",
                    groupName, streamKey);
            return true;
        } catch (Exception e) {
            log.debug("Bootstrap of consumer group '{}' failed: {}", groupName, e.getMessage());
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${app.analytics.poll-interval-ms:5000}")
    public void processBatch() {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redisTemplate.opsForStream().read(
                    Consumer.from(groupName, consumerName),
                    StreamReadOptions.empty().count(batchSize),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        } catch (Exception e) {
            // Self-heal: if the group/stream vanished (e.g. Redis flushed),
            // recreate it so the pipeline resumes on the next tick.
            if (String.valueOf(e.getMessage()).contains("NOGROUP")) {
                log.warn("Consumer group '{}' missing on '{}', recreating", groupName, streamKey);
                ensureConsumerGroup();
                return;
            }
            log.warn("Could not read click events from stream '{}': {}", streamKey, e.getMessage());
            return;
        }
        if (records == null || records.isEmpty()) {
            return;
        }
        processRecords(records);
    }

    void processRecords(List<MapRecord<String, Object, Object>> records) {
        try {
            persistBatch(records);
            acknowledge(records);
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            failedCounter.increment(records.size());
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                log.error("Finalizing click batch of {} events after {} consecutive failures",
                        records.size(), consecutiveFailures, e);
                acknowledge(records);
                consecutiveFailures = 0;
            } else {
                // Leave un-acked: at-least-once redelivery on a later tick
                log.warn("Failed to persist click batch (attempt {}/{}), will retry: {}",
                        consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            }
        }
    }

    private void persistBatch(List<MapRecord<String, Object, Object>> records) {
        Instant consumedAt = Instant.now();
        List<ClickEventDocument> docs = new ArrayList<>(records.size());
        Map<String, Long> clicksPerCode = new HashMap<>();

        for (MapRecord<String, Object, Object> record : records) {
            ClickEventDocument doc = toDocument(record.getValue(), consumedAt);
            if (doc.getShortCode() != null && !doc.getShortCode().isBlank()) {
                docs.add(doc);
                clicksPerCode.merge(doc.getShortCode(), 1L, Long::sum);
            }
        }

        clickEventRepository.insertAll(docs);

        // One atomic $inc per unique code with the exact count for the batch —
        // never read-modify-write, never one increment per event.
        clicksPerCode.forEach(urlRepository::incrementClickCount);
        persistedCounter.increment(docs.size());
    }

    private void acknowledge(List<MapRecord<String, Object, Object>> records) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, groupName,
                    records.stream().map(MapRecord::getId).toArray(org.springframework.data.redis.connection.stream.RecordId[]::new));
        } catch (Exception e) {
            // Ack failure only means redelivery later (at-least-once)
            log.warn("Failed to acknowledge {} click events: {}", records.size(), e.getMessage());
        }
    }

    static ClickEventDocument toDocument(Map<Object, Object> value, Instant consumedAt) {
        String code = asString(value.get(RedisClickEventQueue.FIELD_CODE));
        String ts = asString(value.get(RedisClickEventQueue.FIELD_TIMESTAMP));
        Instant timestamp;
        try {
            timestamp = ts != null ? Instant.parse(ts) : consumedAt;
        } catch (Exception e) {
            timestamp = consumedAt;
        }
        return new ClickEventDocument(code, timestamp,
                asString(value.get(RedisClickEventQueue.FIELD_USER_AGENT)),
                asString(value.get(RedisClickEventQueue.FIELD_IP)));
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }
}
