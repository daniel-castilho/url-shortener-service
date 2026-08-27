package ca.tyny.urlshortener.infra.adapter.output.analytics;

import ca.tyny.urlshortener.infra.adapter.output.persistence.MongoClickEventRepository;
import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled retention purge for the {@code click_events} collection.
 *
 * Runs on a configurable schedule (default daily at 02:00 UTC) and deletes
 * events older than {@code app.analytics.retention-days} (default 90 days)
 * in small batches to avoid long-running transactions and blocking the
 * write path. The purge is idempotent and safe to run concurrently.
 *
 * Metrics:
 *  - {@code analytics.retention.purged.total} — total events deleted across runs
 *  - {@code analytics.retention.runs.total} — number of purge executions
 *  - {@code analytics.retention.errors.total} — purge runs that failed
 */
@Component
public class ClickEventsRetentionPurge {

    private static final Logger log = LoggerFactory.getLogger(ClickEventsRetentionPurge.class);
    private static final int BATCH_SIZE = 1000;
    private static final long MAX_RUN_MS = TimeUnit.MINUTES.toMillis(5);

    private final MongoTemplate mongoTemplate;
    private final MongoClickEventRepository clickEventRepository;
    private final int retentionDays;
    private final Counter purgedCounter;
    private final Counter runsCounter;
    private final Counter errorsCounter;

    public ClickEventsRetentionPurge(MongoTemplate mongoTemplate,
            MongoClickEventRepository clickEventRepository,
            MeterRegistry meterRegistry,
            @Value("${app.analytics.retention-days:90}") int retentionDays) {
        this.mongoTemplate = mongoTemplate;
        this.clickEventRepository = clickEventRepository;
        this.retentionDays = retentionDays;
        this.purgedCounter = Counter.builder("analytics.retention.purged.total")
                .description("Click events purged by the retention policy")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
        this.runsCounter = Counter.builder("analytics.retention.runs.total")
                .description("Retention purge executions")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
        this.errorsCounter = Counter.builder("analytics.retention.errors.total")
                .description("Retention purge errors")
                .tag("pipeline", "clicks")
                .register(meterRegistry);
    }

    /**
     * Runs daily at 02:00 UTC by default. Configurable via {@code app.analytics.retention-cron}.
     * <p>
     * The purge deletes documents where {@code timestamp} is older than the
     * retention window, in batches of {@value #BATCH_SIZE}, stopping after
     * {@value #MAX_RUN_MS} milliseconds to avoid long-running operations.
     */
    @Scheduled(cron = "${app.analytics.retention-cron:0 0 2 * * *}", zone = "UTC")
    public void purge() {
        long startMs = System.currentTimeMillis();
        long cutoff = Instant.now().minusSeconds(retentionDays * 86400L).toEpochMilli();

        log.info("Starting click_events retention purge: retentionDays={}, cutoff={}",
                retentionDays, Instant.ofEpochMilli(cutoff));

        try {
            long totalDeleted = 0;
            long deleted = 0;
            do {
                if (System.currentTimeMillis() - startMs > MAX_RUN_MS) {
                    log.warn("Purge time limit reached ({} ms), stopping early", MAX_RUN_MS);
                    break;
                }

                deleted = deleteBatch(cutoff, BATCH_SIZE);
                totalDeleted += deleted;
            } while (deleted > 0);

            purgedCounter.increment(totalDeleted);
            runsCounter.increment();
            log.info("Retention purge completed: deleted {} events (total this run: {})", deleted, totalDeleted);
        } catch (Exception e) {
            errorsCounter.increment();
            log.error("Retention purge failed", e);
            // Fail-open: do not rethrow, let the scheduler continue
        }
    }

    /**
     * Deletes a single batch of old click events.
     *
     * @param cutoffMillis epoch milliseconds; events with timestamp < cutoff are candidates
     * @param limit maximum documents to delete in this batch
     * @return number of documents actually deleted
     */
    long deleteBatch(long cutoffMillis, int limit) {
        Query query = Query.query(Criteria.where("timestamp").lt(Instant.ofEpochMilli(cutoffMillis)))
                .limit(limit);
        long count = mongoTemplate.remove(query, MongoCollections.CLICK_EVENTS).getDeletedCount();
        if (count > 0) {
            log.debug("Purged {} click events older than {}", count, Instant.ofEpochMilli(cutoffMillis));
        }
        return count;
    }

    /**
     * Exposes the cutoff instant for testing.
     */
    Instant getCutoffInstant() {
        return Instant.now().minusSeconds(retentionDays * 86400L);
    }
}