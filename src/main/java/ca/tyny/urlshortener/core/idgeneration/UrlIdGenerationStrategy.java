package ca.tyny.urlshortener.core.idgeneration;

public interface UrlIdGenerationStrategy {
    /**
     * Checks if this strategy supports the current scenario.
     */
    boolean supports(String customAlias);

    /**
     * Generates (or validates and returns) the URL ID.
     */
    String generateId(String customAlias, String userId);
}
