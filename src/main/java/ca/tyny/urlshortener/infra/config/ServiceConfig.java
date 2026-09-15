package ca.tyny.urlshortener.infra.config;

import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.CompositeUrlIdGenerator;
import ca.tyny.urlshortener.core.idgeneration.RandomUrlIdStrategy;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerationStrategy;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.idgeneration.VanityUrlIdStrategy;
import ca.tyny.urlshortener.core.ports.incoming.ArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.CustomDomainUseCase;
import ca.tyny.urlshortener.core.ports.incoming.GetClickAnalyticsUseCase;
import ca.tyny.urlshortener.core.ports.incoming.GetLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.ListUserLinksUseCase;
import ca.tyny.urlshortener.core.ports.incoming.UpdateLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminBlockUserUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminForceArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminListUserUrlsUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminListUsersUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminLookupUrlUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminUnblockUserUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort;
import ca.tyny.urlshortener.core.ports.outgoing.ClickAnalyticsPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort;
import ca.tyny.urlshortener.core.ports.outgoing.TokenPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.VerificationTokenPort;
import ca.tyny.urlshortener.core.service.AdminBlockUserUseCaseImpl;
import ca.tyny.urlshortener.core.service.AdminForceArchiveLinkUseCaseImpl;
import ca.tyny.urlshortener.core.service.AdminListUserUrlsUseCaseImpl;
import ca.tyny.urlshortener.core.service.AdminListUsersUseCaseImpl;
import ca.tyny.urlshortener.core.service.AdminLookupUrlUseCaseImpl;
import ca.tyny.urlshortener.core.service.AdminUnblockUserUseCaseImpl;
import ca.tyny.urlshortener.core.service.ArchiveLinkUseCaseImpl;
import ca.tyny.urlshortener.core.service.CustomDomainService;
import ca.tyny.urlshortener.core.service.GetClickAnalyticsUseCaseImpl;
import ca.tyny.urlshortener.core.service.GetLinkUseCaseImpl;
import ca.tyny.urlshortener.core.service.ListUserLinksUseCaseImpl;
import ca.tyny.urlshortener.core.service.QuotaService;
import ca.tyny.urlshortener.core.service.UpdateLinkUseCaseImpl;
import ca.tyny.urlshortener.core.service.UrlShortenerService;
import ca.tyny.urlshortener.core.service.UserService;
import ca.tyny.urlshortener.core.validation.ReservedWordsValidator;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import ca.tyny.urlshortener.infra.adapter.input.rest.mapper.LinkMapper;
import ca.tyny.urlshortener.infra.adapter.output.analytics.GeoIpCountryResolver;
import ca.tyny.urlshortener.infra.adapter.output.validation.DefaultUrlValidator;
import ca.tyny.urlshortener.infra.config.properties.AdminProperties;
import ca.tyny.urlshortener.infra.config.properties.AnalyticsProperties;
import ca.tyny.urlshortener.infra.config.properties.DomainProperties;
import ca.tyny.urlshortener.infra.config.properties.ShortenerProperties;
import ca.tyny.urlshortener.infra.config.properties.UrlValidationProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@EnableConfigurationProperties({
  ca.tyny.urlshortener.infra.config.properties.RateLimiterProperties.class,
  ca.tyny.urlshortener.infra.config.properties.RedisClientProperties.class,
  ca.tyny.urlshortener.infra.config.properties.UrlValidationProperties.class,
  ca.tyny.urlshortener.infra.config.properties.SecurityProperties.class,
  ca.tyny.urlshortener.infra.config.properties.ShortenerProperties.class,
  ca.tyny.urlshortener.infra.config.properties.DomainProperties.class,
  ca.tyny.urlshortener.infra.config.properties.AnalyticsProperties.class,
  ca.tyny.urlshortener.infra.config.properties.UrlCacheProperties.class,
  ca.tyny.urlshortener.infra.config.properties.AdminProperties.class
})
public class ServiceConfig {

  @Bean
  public GeoIpCountryResolver geoIpCountryResolver(AnalyticsProperties properties) {
    if (properties.getGeo().isEnabled()) {
      return GeoIpCountryResolver.fromFile(properties.getGeo().getMaxmindDbPath());
    }
    return GeoIpCountryResolver.disabled();
  }

  @Bean
  public Base62CodeGenerator base62CodeGenerator(ShortenerProperties properties) {
    return new Base62CodeGenerator(properties.codeLength());
  }

  @Bean
  public UrlShortenerService urlShortenerService(
      UrlRepositoryPort urlRepository,
      UrlCachePort urlCache,
      MetricsPort metrics,
      UrlIdGenerator urlIdGenerator,
      Base62CodeGenerator base62CodeGenerator,
      QuotaService quotaService,
      UserRepositoryPort userRepository,
      ReservedWordsValidator reservedWordsValidator,
      UrlValidator urlValidator,
      CustomDomainRegistryPort customDomainRegistry,
      CustomDomainRepositoryPort customDomainRepository,
      DomainProperties domainProperties,
      ShortenerProperties shortenerProperties) {
    return new UrlShortenerService(
        urlRepository,
        urlCache,
        metrics,
        urlIdGenerator,
        base62CodeGenerator,
        quotaService,
        userRepository,
        reservedWordsValidator,
        urlValidator,
        customDomainRegistry,
        customDomainRepository,
        domainProperties.defaultHost(),
        shortenerProperties.maxTtlSeconds());
  }

  @Bean
  public UserService userService(
      UserRepositoryPort userRepository,
      PasswordEncoderPort passwordEncoder,
      TokenPort tokenPort,
      AuthenticationPort authenticationPort,
      IdGeneratorPort idGeneratorPort,
      AdminProperties adminEmailPort) {
    return new UserService(
        userRepository,
        passwordEncoder,
        tokenPort,
        authenticationPort,
        idGeneratorPort,
        adminEmailPort);
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
  public VanityUrlIdStrategy vanityUrlIdStrategy(
      UserRepositoryPort userRepository, UrlRepositoryPort urlRepository) {
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
  public UrlValidator urlValidator(UrlValidationProperties properties, MetricsPort metricsPort) {
    return new DefaultUrlValidator(properties, metricsPort);
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
  public GetClickAnalyticsUseCase getClickAnalyticsUseCase(
      GetLinkUseCase getLinkUseCase, ClickAnalyticsPort clickAnalyticsPort) {
    return new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);
  }

  @Bean
  public UpdateLinkUseCase updateLinkUseCase(
      LinkQueryPort linkQueryPort,
      LinkMutationPort linkMutationPort,
      UrlCachePort urlCachePort,
      UrlValidator urlValidator,
      CustomDomainRepositoryPort customDomainRepository,
      ShortenerProperties properties) {
    return new UpdateLinkUseCaseImpl(
        linkQueryPort,
        linkMutationPort,
        urlCachePort,
        urlValidator,
        customDomainRepository,
        properties.maxTtlSeconds());
  }

  @Bean
  public ArchiveLinkUseCase archiveLinkUseCase(
      LinkQueryPort linkQueryPort, LinkMutationPort linkMutationPort, UrlCachePort urlCachePort) {
    return new ArchiveLinkUseCaseImpl(linkQueryPort, linkMutationPort, urlCachePort);
  }

  @Bean
  public AdminListUsersUseCase adminListUsersUseCase(
      UserRepositoryPort userRepository, AdminProperties adminEmailPort) {
    return new AdminListUsersUseCaseImpl(userRepository, adminEmailPort);
  }

  @Bean
  public AdminBlockUserUseCase adminBlockUserUseCase(UserRepositoryPort userRepository) {
    return new AdminBlockUserUseCaseImpl(userRepository);
  }

  @Bean
  public AdminUnblockUserUseCase adminUnblockUserUseCase(UserRepositoryPort userRepository) {
    return new AdminUnblockUserUseCaseImpl(userRepository);
  }

  @Bean
  public AdminListUserUrlsUseCase adminListUserUrlsUseCase(
      UserRepositoryPort userRepository, LinkQueryPort linkQueryPort) {
    return new AdminListUserUrlsUseCaseImpl(userRepository, linkQueryPort);
  }

  @Bean
  public AdminLookupUrlUseCase adminLookupUrlUseCase(
      LinkQueryPort linkQueryPort, UserRepositoryPort userRepository) {
    return new AdminLookupUrlUseCaseImpl(linkQueryPort, userRepository);
  }

  @Bean
  public AdminForceArchiveLinkUseCase adminForceArchiveLinkUseCase(
      LinkQueryPort linkQueryPort, LinkMutationPort linkMutationPort, UrlCachePort urlCachePort) {
    return new AdminForceArchiveLinkUseCaseImpl(linkQueryPort, linkMutationPort, urlCachePort);
  }

  @Bean
  public CustomDomainUseCase customDomainUseCase(
      CustomDomainRepositoryPort customDomainRepository,
      VerificationTokenPort verificationTokenPort,
      DomainProperties domainProperties) {
    return new CustomDomainService(
        customDomainRepository, verificationTokenPort, domainProperties.defaultHost());
  }
}
