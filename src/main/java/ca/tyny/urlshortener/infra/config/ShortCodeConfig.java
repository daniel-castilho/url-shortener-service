package ca.tyny.urlshortener.infra.config;

import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShortCodeConfig {

    @Bean
    public IdGeneratorPort idGeneratorPort(Base62CodeGenerator base62CodeGenerator) {
        return base62CodeGenerator::generate;
    }
}
