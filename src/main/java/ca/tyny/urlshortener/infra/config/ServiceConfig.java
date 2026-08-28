package ca.tyny.urlshortener.infra.config;

import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.CompositeUrlIdGenerator;
import ca.tyny.urlshortener.core.idgeneration.RandomUrlIdStrategy;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerationStrategy;
import ca.tyny.urlshortener.core.idgeneration.VanityUrlIdStrategy;
import ca.tyny.urlshortener.core.ports.incoming.ArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.GetLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.ListUserLinksUseCase;
import ca.tyny.urlshortener.core.ports.incoming.UpdateLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort;
import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort;
import ca.tyny.urlshortener.core.ports.outgoing.TokenPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.service.ArchiveLinkUseCaseImpl;
import ca.tyny.urlshortener.core.service.GetLinkUseCaseImpl;
import ca.tyny.urlshortener.core.service.ListUserLinksUseCaseImpl;
import ca.tyny.urlshortener.core.service.QuotaService;
import ca.tyny.urlshortener.core.service.UrlShortenerService;
import ca.tyny.urlshortener.core.service.UpdateLinkUseCaseImpl;
import ca.tyny.urlshortener.core.service.UserService;
import ca.tyny.urlshortener.core.validation.ReservedWordsValidator;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import ca.tyny.urlshortener.infra.adapter.input.rest.mapper.LinkMapper;
import ca.tyny.urlshortener.infra.adapter.output.persistence.mapper.ShortUrlMapper;
import ca.tyny.urlshortener.infra.adapter.output.validation.DefaultUrlValidator;
import ca.tyny.urlshortener.infra.config.properties.RateLimiterProperties;
import ca.tyny.urlshortener.infra.config.properties.ShortenerProperties;
import ca.tyny.urlshortener.infra.config.properties.UrlValidationProperties;
import ca.tyny.urlshortener.infra.config.properties.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

@Configuration
@EnableConfigurationProperties({
        ca.tyny.urlshortener.infra.config.properties.RateLimiterProperties.class,
        ca.tyny.urlshortener.infra.config.properties.UrlValidationProperties.class,
        ca.tyny.urlshortener.infra.config.properties.SecurityProperties.class,
        ca.tyny.urlshortener.infra.config.properties.ShortenerProperties.class
})
public class ServiceConfig {

    @Bean
    public Base62CodeGenerator base62CodeGenerator(ShortenerProperties properties) {
        return new Base62CodeGenerator(properties.codeLength());
    }

    @Bean
    public UrlShortenerService urlShortenerService(UrlRepositoryPort urlRepository,
            UrlCachePort urlCache,
            MetricsPort metrics,
            UrlIdGenerator urlIdGenerator,
            Base62CodeGenerator base62CodeGenerator,
            QuotaService quotaService,
            UserRepositoryPort userRepository,
            ReservedWordsValidator reservedWordsValidator,
            UrlValidator urlValidator) {
        return new UrlShortenerService(urlRepository, urlCache, metrics, urlIdGenerator,
                base62CodeGenerator, quotaService, userRepository, reservedWordsValidator, urlValidator);
    }

    @Bean
    public UserService userService(UserRepositoryPort userRepository,
            PasswordEncoderPort passwordEncoder,
            TokenPort tokenPort,
            AuthenticationPort authenticationPort,
            IdGeneratorPort idGeneratorPort) {
        return new UserService(userRepository, passwordEncoder, tokenPort, authenticationPort, idGeneratorPort);
    }

    @Bean
    public ReservedWordsValidator reservedWordsValidator() {
        return new ReservedWordsValidator();
    }

    @Bean
    public RandomUrlIdStrategy randomUrlIdStrategy(IdGeneratorPort idGenerator) {
        return new RandomUrlIdStrategy(idGenerator);
    }

    @Bean
    @Order(1)
    public VanityUrlIdStrategy vanityUrlIdStrategy(UserRepositoryPort userRepository,
            UrlRepositoryPort urlRepository) {
        return new VanityUrlIdStrategy(userRepository, urlRepository);
    }

    @Bean
    public QuotaService quotaService(UserRepositoryPort userRepository) {
        return new QuotaService(userRepository);
    }

    @Bean
    public CompositeUrlIdGenerator compositeUrlIdGenerator(List<UrlIdGenerationStrategy> strategies) {
        return new CompositeUrlIdGenerator(strategies);
    }

    @Bean
    public UrlValidator urlValidator(UrlValidationProperties properties) {
        return new DefaultUrlValidator(properties);
    }

    @Bean
    public LinkMapper linkMapper() {
        return new LinkMapper();
    }

    @Bean
    public ListUserLinksUseCase listUserLinksUseCase(LinkQueryPort linkQueryPort) {
        return new ListUserLinksUseCaseImpl(linkQueryPort);
    }

    @Bean
    public GetLinkUseCase getLinkUseCase(LinkQueryPort linkQueryPort) {
        return new GetLinkUseCaseImpl(linkQueryPort);
    }

    @Bean
    public UpdateLinkUseCase updateLinkUseCase(LinkQueryPort linkQueryPort,
            LinkMutationPort linkMutationPort,
            UrlCachePort urlCachePort,
            UrlValidator urlValidator,
            ShortenerProperties properties) {
        return new UpdateLinkUseCaseImpl(linkQueryPort, linkMutationPort, urlCachePort, urlValidator, properties.maxTtlSeconds());
    }

    @Bean
    public ArchiveLinkUseCase archiveLinkUseCase(LinkQueryPort linkQueryPort,
            LinkMutationPort linkMutationPort,
            UrlCachePort urlCachePort) {
        return new ArchiveLinkUseCaseImpl(linkQueryPort, linkMutationPort, urlCachePort);
    }
}