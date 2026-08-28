package ca.tyny.urlshortener.core.model;

public record UtmParams(
        String source,
        String medium,
        String campaign,
        String term,
        String content
) {}