package ca.tyny.urlshortener.infra.adapter.output.analytics;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled, idempotent, bounded aggregation of raw {@code click_events} into
 * the {@code click_daily} rollup collection.
 *
 * <p>Runs daily (default 01:10 UTC) and aggregates the previous
 * {@code app.analytics.rollup-days} day(s), each day independently: raw events
 * are grouped by {@code (shortCode, day)} with {@code clicks} plus per-value
 * breakdown counts for {@code device}, {@code country} and {@code referrer},
 * then upserted on the unique {@code (shortCode, day)} key. Because a closed
 * day is immutable, re-running produces the same values — the upsert makes the
 * job idempotent.
 *
 * <p>Bounded: per day the grouped output is capped, the whole run observes a
 * wall-clock limit, and failures are per-day fail-open (logged + metered) so a
 * transient error cannot wedge later days.
 */
@Component
public class ClickDailyRollupJob {

    private static final Logger log = LoggerFactory.getLogger(ClickDailyRollupJob.class);

    private static final List<String> BREAKDOWN_FIELDS = List.of("device", "country", "referrer");
    private static final int MAX_GROUPS_PER_DAY = 50_000;
    private static final String NO_VALUE = "(none)";
    private static final long MAX_RUN_MS = TimeUnit.HOURS.toMillis(2);

    private final MongoTemplate mongoTemplate;
    private final int rollupDays;
    private final Counter upsertedCounter;
    private final Counter daysCounter;
    private final Counter errorsCounter;

    public ClickDailyRollupJob(MongoTemplate mongoTemplate,
            MeterRegistry meterRegistry,
            @Value("${app.analytics.rollup-days:1}") int rollupDays) {
        this.mongoTemplate = mongoTemplate;
        this.rollupDays = Math.max(1, rollupDays);
        this.upsertedCounter = Counter.builder("analytics.rollup.groups.upserted.total")
                .description("(shortCode, day) rollup rows written to click_daily")
                .tag("pipeline", "rollup")
                .register(meterRegistry);
        this.daysCounter = Counter.builder("analytics.rollup.days.total")
                .description("Calendar days aggregated by the rollup job")
                .tag("pipeline", "rollup")
                .register(meterRegistry);
        this.errorsCounter = Counter.builder("analytics.rollup.errors.total")
                .description("Rollup runs or days that failed")
                .tag("pipeline", "rollup")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${app.analytics.rollup-cron:0 10 1 * * *}", zone = "UTC")
    public void rollup() {
        log.info("Starting click_daily rollup for the last {} day(s)", rollupDays);
        long startMs = System.currentTimeMillis();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int offset = 1; offset <= rollupDays; offset++) {
            if (System.currentTimeMillis() - startMs > MAX_RUN_MS) {
                log.warn("Rollup time limit reached ({} ms), stopping early", MAX_RUN_MS);
                break;
            }
            rollupDay(today.minusDays(offset));
        }
    }

    /**
     * Aggregates a single closed UTC day. Ignores events from other days, other
     * short codes and blank codes. Package-visible for integration tests.
     */
    void rollupDay(LocalDate day) {
        String dayString = day.toString();
        try {
            Map<String, DailyRow> rows = new LinkedHashMap<>();
            for (String field : BREAKDOWN_FIELDS) {
                Map<String, DimensionAggregate> aggregates = aggregateDimension(day, field);
                aggregates.forEach((code, agg) -> rows.computeIfAbsent(code, c -> new DailyRow())
                        .merge(field, agg.clicks, agg.dims));
            }
            for (Map.Entry<String, DailyRow> e : rows.entrySet()) {
                upsert(dayString, e.getKey(), e.getValue());
                upsertedCounter.increment();
            }
            daysCounter.increment();
            log.info("Rollup for {}: {} (shortCode, day) rows", dayString, rows.size());
        } catch (Exception e) {
            errorsCounter.increment();
            log.error("Rollup failed for {}", dayString, e);
        }
    }

    private Map<String, DimensionAggregate> aggregateDimension(LocalDate day, String field) {
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = start.plus(1, ChronoUnit.DAYS);

        List<Document> pipeline = List.of(
                new Document("$match", new Document("timestamp",
                        new Document("$gte", start).append("$lt", end))),
                new Document("$group", new Document("_id",
                        new Document("code", "$shortCode").append("value", "$" + field))
                        .append("count", new Document("$sum", 1))),
                new Document("$group", new Document("_id", "$_id.code")
                        .append("clicks", new Document("$sum", "$count"))
                        .append("pairs", new Document("$push",
                                new Document("k", new Document("$ifNull",
                                        List.of("$_id.value", NO_VALUE)))
                                        .append("v", "$count")))),
                new Document("$project", new Document("_id", 0)
                        .append("code", "$_id")
                        .append("clicks", 1)
                        .append("dims", new Document("$arrayToObject", "$pairs"))),
                new Document("$limit", MAX_GROUPS_PER_DAY));

        List<Document> results = mongoTemplate.getMongoDatabaseFactory()
                .getMongoDatabase()
                .getCollection(MongoCollections.CLICK_EVENTS)
                .aggregate(pipeline)
                .into(new ArrayList<>());

        Map<String, DimensionAggregate> byCode = new LinkedHashMap<>();
        for (Document doc : results) {
            byCode.put(doc.getString("code"),
                    new DimensionAggregate(((Number) doc.get("clicks")).longValue(),
                            toCountMap(doc.get("dims"))));
        }
        return byCode;
    }

    private static Map<String, Long> toCountMap(Object dims) {
        if (!(dims instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        raw.forEach((k, v) -> counts.put(String.valueOf(k), ((Number) v).longValue()));
        return counts;
    }

    private void upsert(String dayString, String shortCode, DailyRow row) {
        Update update = new Update()
                .set("shortCode", shortCode)
                .set("day", dayString)
                .set("clicks", row.clicks)
                .set("uniqueDays", 1L)
                .set("breakdown", row.breakdown)
                .set("updatedAt", Instant.now());
        mongoTemplate.upsert(
                Query.query(Criteria.where("shortCode").is(shortCode).and("day").is(dayString)),
                update,
                MongoCollections.CLICK_DAILY);
    }

    /** A single (shortCode, day) row being assembled from the per-dimension runs. */
    private static final class DailyRow {
        private long clicks;
        private final Map<String, Map<String, Long>> breakdown = new LinkedHashMap<>();

        void merge(String field, long dimClicks, Map<String, Long> dims) {
            this.clicks = dimClicks;
            this.breakdown.put(field, dims);
        }
    }

    private record DimensionAggregate(long clicks, Map<String, Long> dims) {
    }
}