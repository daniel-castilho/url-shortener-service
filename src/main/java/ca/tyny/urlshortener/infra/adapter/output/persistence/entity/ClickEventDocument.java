package ca.tyny.urlshortener.infra.adapter.output.persistence.entity;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persistence document for a single click event.
 *
 * Stored as provenance for analytics aggregates; the per-link running total
 * lives on {@code short_urls.clickCount} and is maintained atomically ($inc).
 * Timestamps are UTC instants; the domain ClickEvent keeps LocalDateTime and
 * the conversion happens at the adapter boundary.
 */
@Document(collection = MongoCollections.CLICK_EVENTS)
public class ClickEventDocument {

    @Id
    private String id;

    /** The resolved short URL code (reference, not a nested document). */
    private String shortCode;

    /** When the click occurred (producer-side, UTC). */
    private Instant timestamp;

    private String userAgent;

    private String ip;

    /** When the worker actually persisted the event (UTC). */
    private Instant consumedAt;

    public ClickEventDocument() {
    }

    public ClickEventDocument(String shortCode, Instant timestamp, String userAgent, String ip) {
        this.shortCode = shortCode;
        this.timestamp = timestamp;
        this.userAgent = userAgent;
        this.ip = ip;
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }
}
