package ca.tyny.urlshortener.core.idgeneration;

import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;

public class RandomUrlIdStrategy implements UrlIdGenerationStrategy {

    private final IdGeneratorPort idGenerator;

    public RandomUrlIdStrategy(IdGeneratorPort idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public boolean supports(String customAlias) {
        return customAlias == null || customAlias.isBlank();
    }

    @Override
    public String generateId(String customAlias, String userId) {
        return idGenerator.generateId();
    }
}
