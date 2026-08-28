package ca.tyny.urlshortener.infra.adapter.output.persistence.entity;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Per-day click rollup row for cheap temporal analytics.
 *
 * <p>One row per {@code (shortCode, day)} pair, computed by the scheduled
 * aggregation job from the raw {@code click_events} collection. {@code day}
 * is the UTC calendar day ({@code yyyy-MM-dd}). {@code breakdown} maps a
 * dimension ({@code device | country | referrer}) to a value-to-count map.
 * The {@code (shortCode, day)} pair is unique (V9) so re-runs of the rollup
 * overwrite the row instead of duplicating it.
 */
@Document(collection = MongoCollections.CLICK_DAILY)
public class ClickDailyDocument {

    @Id
    private String id;

    private String shortCode;

    private String day;

    private long clicks;

    /** Number of distinct days represented by this row (always 1 here). */
    private long uniqueDays;

    private Map<String, Map<String, Long>> breakdown;

    private Instant updatedAt;

    public ClickDailyDocument() {
    }

    public ClickDailyDocument(String shortCode, String day, long clicks, long uniqueDays,
            Map<String, Map<String, Long>> breakdown, Instant updatedAt) {
        this.shortCode = shortCode;
        this.day = day;
        this.clicks = clicks;
        this.uniqueDays = uniqueDays;
        this.breakdown = breakdown;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public long getClicks() {
        return clicks;
    }

    public void setClicks(long clicks) {
        this.clicks = clicks;
    }

    public long getUniqueDays() {
        return uniqueDays;
    }

    public void setUniqueDays(long uniqueDays) {
        this.uniqueDays = uniqueDays;
    }

    public Map<String, Map<String, Long>> getBreakdown() {
        return breakdown;
    }

    public void setBreakdown(Map<String, Map<String, Long>> breakdown) {
        this.breakdown = breakdown;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}