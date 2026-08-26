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

    private MongoCollections() {
        throw new AssertionError("Utility class should not be instantiated");
    }
}

