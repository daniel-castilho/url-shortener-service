package ca.tyny.urlshortener.core.idgeneration;

import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RandomUrlIdStrategy implements UrlIdGenerationStrategy {

    private final IdGeneratorPort idGenerator;

    @Override
    public boolean supports(String customAlias) {
        return customAlias == null || customAlias.isBlank();
    }

    @Override
    public String generateId(String customAlias, String userId) {
        return idGenerator.generateId();
    }
}
