package ca.tyny.urlshortener.infra.adapter.output.persistence.config;

/**
 * Centralizes MongoDB collection name constants.
 * Avoids magic strings scattered across the codebase (DRY principle).
 *
 * Facilitates future refactoring if collection names need to change.
 */
public class MongoCollections {

    /**
     * Collection storing shortened URLs.
     * Used for entity mapping via @Document(collection = MongoCollections.SHORT_URLS)
     */
    public static final String SHORT_URLS = "short_urls";

    /**
     * Collection storing persisted click events.
     * Written in batches by the analytics worker; queried by shortCode/timestamp.
     */
    public static final String CLICK_EVENTS = "click_events";

    private MongoCollections() {
        throw new AssertionError("Utility class should not be instantiated");
    }
}

