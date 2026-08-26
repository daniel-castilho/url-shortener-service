package ca.tyny.urlshortener.infra.config;

import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort;
import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort;
import ca.tyny.urlshortener.core.ports.outgoing.TokenPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.service.QuotaService;
import ca.tyny.urlshortener.core.service.UserService;
import ca.tyny.urlshortener.core.validation.ReservedWordsValidator;
import ca.tyny.urlshortener.core.service.UrlShortenerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceConfig {

    @Bean
    public Base62CodeGenerator base62CodeGenerator(
            @Value("${app.shortener.code-length:7}") int codeLength) {
        return new Base62CodeGenerator(codeLength);
    }

    @Bean
    public UrlShortenerService urlShortenerService(UrlRepositoryPort urlRepository,
            UrlCachePort urlCache,
            MetricsPort metrics,
            UrlIdGenerator urlIdGenerator,
            Base62CodeGenerator base62CodeGenerator,
            QuotaService quotaService,
            UserRepositoryPort userRepository,
            ReservedWordsValidator reservedWordsValidator) {
        return new UrlShortenerService(urlRepository, urlCache, metrics, urlIdGenerator,
                base62CodeGenerator, quotaService, userRepository, reservedWordsValidator);
    }

    @Bean
    public UserService userService(UserRepositoryPort userRepository,
            PasswordEncoderPort passwordEncoder,
            TokenPort tokenPort,
            AuthenticationPort authenticationPort,
            IdGeneratorPort idGeneratorPort) {
        return new UserService(userRepository, passwordEncoder, tokenPort, authenticationPort, idGeneratorPort);
    }
}
