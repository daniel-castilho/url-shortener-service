package ca.tyny.urlshortener.core.idgeneration;

import java.util.List;

public class CompositeUrlIdGenerator implements UrlIdGenerator {

    private final List<UrlIdGenerationStrategy> strategies;

    public CompositeUrlIdGenerator(List<UrlIdGenerationStrategy> strategies) {
        this.strategies = strategies;
    }

    @Override
    public String generateId(String customAlias, String userId) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(customAlias))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No valid strategy found for ID generation"))
                .generateId(customAlias, userId);
    }
}
