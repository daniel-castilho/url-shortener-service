package com.example.urlshortener.core.idgeneration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CompositeUrlIdGenerator implements UrlIdGenerator {

    private final List<UrlIdGenerationStrategy> strategies;

    @Override
    public String generateId(String customAlias, String userId) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(customAlias))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No valid strategy found for ID generation"))
                .generateId(customAlias, userId);
    }
}
