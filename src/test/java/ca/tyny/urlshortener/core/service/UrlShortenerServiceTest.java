package ca.tyny.urlshortener.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.exception.CodeGenerationException;
import ca.tyny.urlshortener.core.exception.DomainNotVerifiedException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidDomainException;
import ca.tyny.urlshortener.core.exception.ShortCodeCollisionException;
import ca.tyny.urlshortener.core.exception.UrlExpiredException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.model.QuotaUsage;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.SubscriptionPlan;
import ca.tyny.urlshortener.core.model.SubscriptionStatus;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlShortenerService Tests")
class UrlShortenerServiceTest {

  @Mock private UrlRepositoryPort urlRepository;

  @Mock private UrlCachePort urlCache;

  @Mock private MetricsPort metrics;

  @Mock private UrlIdGenerator urlIdGenerator;

  @Mock private QuotaService quotaService;

  @Mock private UserRepositoryPort userRepository;

  @Mock private ca.tyny.urlshortener.core.validation.ReservedWordsValidator reservedWordsValidator;

  @Mock private UrlValidator urlValidator;

  @Mock
  private ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort customDomainRegistry;

  @Mock
  private ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort
      customDomainRepository;

  private Base62CodeGenerator base62CodeGenerator;

  private UrlShortenerService service;

  private static final String TEST_URL = "https://www.example.com/very/long/url";
  private static final String TEST_ID = "abc123";
  private static final String DEFAULT_HOST = "localhost";

  @BeforeEach
  void setUp() {
    base62CodeGenerator = new Base62CodeGenerator(7);
    lenient().doNothing().when(urlValidator).validate(anyString());
    lenient().when(customDomainRegistry.isActiveHost(anyString())).thenReturn(false);
    service =
        new UrlShortenerService(
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
            DEFAULT_HOST);
  }

  @Test
  @DisplayName("Should shorten URL using Base62 code generator")
  @TracesRequirement("REQ-SHORT-001")
  void shouldShortenUrl() {
    // When
    ShortUrl result = service.shorten(TEST_URL);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.id()).hasSize(7);
    assertThat(result.id()).matches("^[0-9A-Za-z]+$");
    assertThat(result.originalUrl()).isEqualTo(TEST_URL);

    verify(urlRepository).save(any(ShortUrl.class));
  }

  @Test
  @DisplayName("Should pass custom alias and user ID to Generator")
  @TracesRequirement("REQ-SHORT-001")
  void shouldPassParamsToGenerator() {
    // Given
    String customAlias = "my-alias";
    String userId = "user123";
    when(urlIdGenerator.generateId(customAlias, userId)).thenReturn(customAlias);

    // When
    ShortUrl result = service.shorten(TEST_URL, customAlias, userId);

    // Then
    assertThat(result.id()).isEqualTo(customAlias);
    assertThat(result.userId()).isEqualTo(userId);

    verify(urlIdGenerator).generateId(customAlias, userId);
  }

  @Test
  @DisplayName("Should retry on collision and succeed")
  @TracesRequirement("REQ-SHORT-001")
  void shouldRetryOnCollision() {
    // Given: first save throws (collision), second succeeds
    doThrow(new ShortCodeCollisionException("abc123"))
        .doNothing()
        .when(urlRepository)
        .save(any(ShortUrl.class));

    // When
    ShortUrl result = service.shorten(TEST_URL);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.id()).hasSize(7);
    verify(urlRepository, times(2)).save(any(ShortUrl.class));
  }

  @Test
  @DisplayName("Should throw on retry exhaustion")
  @TracesRequirement("REQ-SHORT-001")
  void shouldThrowOnRetryExhaustion() {
    // Given: every save throws collision
    doThrow(new ShortCodeCollisionException("abc123"))
        .when(urlRepository)
        .save(any(ShortUrl.class));

    // When/Then
    assertThatThrownBy(() -> service.shorten(TEST_URL))
        .isInstanceOf(CodeGenerationException.class)
        .hasMessageContaining("Failed to generate a unique code");

    verify(urlRepository, times(UrlShortenerService.MAX_COLLISION_RETRIES + 1))
        .save(any(ShortUrl.class));
  }

  @Test
  @DisplayName("Blocked account cannot shorten — 403 before any side effect (ADR 0011 D4)")
  void shouldRejectShortenForBlockedAccount() {
    // Only runs when a session is present (userId != null); anonymous stays allowed.
    when(userRepository.findById("blocked-user"))
        .thenReturn(Optional.of(blockedUser("blocked-user")));

    assertThatThrownBy(() -> service.shorten(TEST_URL, null, "blocked-user"))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("Account blocked.");

    // nothing written, nothing counted, no code generated
    verify(urlRepository, never()).save(any());
    verify(quotaService, never()).incrementVanityUrlUsage(any());
    verify(urlIdGenerator, never()).generateId(any(), any());
  }

  @Test
  @DisplayName("Anonymous shorten is not affected by any account block (ADR 0011 D4)")
  void anonymousShortenBypassesAccountBlock() {
    // no lookup is performed for anonymous sessions
    ShortUrl result = service.shorten(TEST_URL);

    assertThat(result).isNotNull();
    verify(userRepository, never()).findById(any());
    verify(urlRepository).save(any(ShortUrl.class));
  }

  private User blockedUser(String id) {
    return new User(
        id,
        "blocked@example.com",
        "Blocked",
        true,
        "hash",
        SubscriptionPlan.FREE,
        SubscriptionStatus.ACTIVE,
        LocalDateTime.now(),
        null,
        new QuotaUsage(),
        null,
        null,
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  @Test
  @DisplayName("Should get original URL from cache (Cache Hit)")
  @TracesRequirement("REQ-SHORT-004")
  void shouldGetOriginalUrlFromCache() {
    // Given
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null)));

    // When
    String result = service.getOriginalUrl(DEFAULT_HOST, TEST_ID);

    // Then
    assertThat(result).isEqualTo(TEST_URL);
    verify(urlCache).lookup(TEST_ID);
    verify(urlRepository, never()).findById(any());
  }

  @Test
  @DisplayName("Should get original URL from DB and populate cache (Cache Miss)")
  @TracesRequirement("REQ-SHORT-004")
  void shouldGetOriginalUrlFromDbAndPopulateCache() {
    // Given
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
    ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
    when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(shortUrl));

    // When
    String result = service.getOriginalUrl(DEFAULT_HOST, TEST_ID);

    // Then
    assertThat(result).isEqualTo(TEST_URL);
    verify(urlCache).lookup(TEST_ID);
    verify(urlRepository).findById(TEST_ID);
    verify(urlCache).put(TEST_ID, new CachedUrlValue(TEST_URL, null));
  }

  @Test
  @DisplayName("Should throw UrlExpiredException and not populate cache when short URL is expired")
  @TracesRequirement("REQ-SHORT-004")
  void shouldThrowUrlExpiredWhenShortUrlExpired() {
    // Given
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
    ShortUrl expired =
        new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now())
            .withExpiresAt(Instant.now().minusSeconds(60));
    when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(expired));

    // When
    assertThatThrownBy(() -> service.getOriginalUrl(DEFAULT_HOST, TEST_ID))
        .isInstanceOf(UrlExpiredException.class)
        .hasMessageContaining(TEST_ID);

    // Then
    verify(urlCache, never()).put(eq(TEST_ID), any(CachedUrlValue.class));
    verify(urlRepository).findById(TEST_ID);
    verify(metrics).recordUrlExpired();
  }

  @Test
  @DisplayName("Should serve a short URL that has not expired")
  @TracesRequirement("REQ-SHORT-004")
  void shouldServeNonExpiredShortUrl() {
    // Given
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
    ShortUrl shortUrl =
        new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now())
            .withExpiresAt(Instant.now().plusSeconds(3600));
    when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(shortUrl));

    // When
    String result = service.getOriginalUrl(DEFAULT_HOST, TEST_ID);

    // Then
    assertThat(result).isEqualTo(TEST_URL);
    verify(urlRepository).findById(TEST_ID);
    verify(urlCache).put(TEST_ID, new CachedUrlValue(TEST_URL, shortUrl.expiresAt()));
  }

  @Test
  @DisplayName("Should record id generation duration metric on shorten")
  @TracesRequirement("REQ-SHORT-001")
  void shouldRecordIdGenerationMetric() {
    service.shorten(TEST_URL);

    verify(metrics).recordIdGeneration(any(Duration.class));
  }

  @Test
  @DisplayName("Should record url retrieval duration metric on cache hit")
  @TracesRequirement("REQ-SHORT-004")
  void shouldRecordUrlRetrievalMetricOnHit() {
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null)));

    service.getOriginalUrl(DEFAULT_HOST, TEST_ID);

    verify(metrics).recordUrlRetrieval(any(Duration.class));
  }

  @Test
  @DisplayName("Should serve a cached value that has not expired")
  @TracesRequirement("REQ-SHORT-004")
  void shouldServeNonExpiredCachedValue() {
    // Given
    when(urlCache.lookup(TEST_ID))
        .thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, Instant.now().plusSeconds(3600))));

    // When
    String result = service.getOriginalUrl(DEFAULT_HOST, TEST_ID);

    // Then
    assertThat(result).isEqualTo(TEST_URL);
    verify(urlRepository, never()).findById(any());
  }

  @Test
  @DisplayName("Should throw UrlExpiredException for an expired cached value")
  @TracesRequirement("REQ-SHORT-004")
  void shouldThrowUrlExpiredForExpiredCachedValue() {
    // Given
    when(urlCache.lookup(TEST_ID))
        .thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, Instant.now().minusSeconds(60))));

    // When
    assertThatThrownBy(() -> service.getOriginalUrl(DEFAULT_HOST, TEST_ID))
        .isInstanceOf(UrlExpiredException.class);

    // Then
    verify(urlRepository, never()).findById(any());
    verify(metrics).recordUrlExpired();
  }

  @Test
  @DisplayName("Should record url retrieval duration metric on cache miss")
  @TracesRequirement("REQ-SHORT-004")
  void shouldRecordUrlRetrievalMetricOnMiss() {
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.miss());
    ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
    when(urlRepository.findById(TEST_ID)).thenReturn(Optional.of(shortUrl));

    service.getOriginalUrl(DEFAULT_HOST, TEST_ID);

    verify(metrics).recordUrlRetrieval(any(Duration.class));
  }

  @Test
  @DisplayName("Should propagate expiry onto an auto-generated short URL")
  @TracesRequirement("REQ-SHORT-001")
  void shouldPropagateExpiresAtOnAutoCode() {
    java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);

    ShortUrl result = service.shorten(TEST_URL, null, null, expiry);

    assertThat(result.expiresAt()).isEqualTo(expiry);
    org.mockito.ArgumentCaptor<ShortUrl> captor =
        org.mockito.ArgumentCaptor.forClass(ShortUrl.class);
    verify(urlRepository).save(captor.capture());
    assertThat(captor.getValue().expiresAt()).isEqualTo(expiry);
  }

  @Test
  @DisplayName("Should propagate expiry onto a vanity alias short URL")
  @TracesRequirement("REQ-SHORT-002")
  void shouldPropagateExpiresAtOnVanityAlias() {
    java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
    String customAlias = "my-alias";
    String userId = "user123";
    when(urlIdGenerator.generateId(customAlias, userId)).thenReturn(customAlias);

    ShortUrl result = service.shorten(TEST_URL, customAlias, userId, expiry);

    assertThat(result.id()).isEqualTo(customAlias);
    assertThat(result.expiresAt()).isEqualTo(expiry);
  }

  @Test
  @DisplayName("Should leave expiresAt null when no TTL is provided")
  @TracesRequirement("REQ-SHORT-001")
  void shouldLeaveExpiresAtNullByDefault() {
    ShortUrl result = service.shorten(TEST_URL);

    assertThat(result.expiresAt()).isNull();
  }

  // ========== Host-aware resolution (strict mirror) ==========

  @Test
  @DisplayName("Default host rejects a custom-domain-bound link")
  @TracesRequirement("REQ-SHORT-004")
  void defaultHostRejectsBoundLink() {
    when(urlCache.lookup(TEST_ID))
        .thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null, "links.example.com")));

    assertThatThrownBy(() -> service.getOriginalUrl(DEFAULT_HOST, TEST_ID))
        .isInstanceOf(UrlNotFoundException.class);
    verify(urlRepository, never()).findById(any());
  }

  @Test
  @DisplayName("Custom host serves a link bound to it")
  @TracesRequirement("REQ-SHORT-004")
  void customHostServesBoundLink() {
    when(customDomainRegistry.isActiveHost("links.example.com")).thenReturn(true);
    when(urlCache.lookup(TEST_ID))
        .thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null, "links.example.com")));

    String result = service.getOriginalUrl("links.example.com", TEST_ID);

    assertThat(result).isEqualTo(TEST_URL);
    verify(urlRepository, never()).findById(any());
  }

  @Test
  @DisplayName("Custom host rejects a link bound to a different host")
  @TracesRequirement("REQ-SHORT-004")
  void customHostRejectsDifferentDomain() {
    when(customDomainRegistry.isActiveHost("links.example.com")).thenReturn(true);
    when(urlCache.lookup(TEST_ID))
        .thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null, "other.example.org")));

    assertThatThrownBy(() -> service.getOriginalUrl("links.example.com", TEST_ID))
        .isInstanceOf(UrlNotFoundException.class);
  }

  @Test
  @DisplayName("Custom host without a binding resolves nothing")
  @TracesRequirement("REQ-SHORT-004")
  void customHostWithoutBindingIsNotFound() {
    when(customDomainRegistry.isActiveHost("links.example.com")).thenReturn(true);
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null)));

    assertThatThrownBy(() -> service.getOriginalUrl("links.example.com", TEST_ID))
        .isInstanceOf(UrlNotFoundException.class);
  }

  @Test
  @DisplayName("Unknown host is rejected before any lookup")
  @TracesRequirement("REQ-SHORT-004")
  void unknownHostIsRejected() {
    assertThatThrownBy(() -> service.getOriginalUrl("unrelated.example.net", TEST_ID))
        .isInstanceOf(UrlNotFoundException.class);
    verify(urlCache, never()).lookup(anyString());
  }

  @Test
  @DisplayName("Null host is treated as the default host")
  @TracesRequirement("REQ-SHORT-007")
  void nullHostFallsBackToDefault() {
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null)));

    assertThat(service.getOriginalUrl(null, TEST_ID)).isEqualTo(TEST_URL);
  }

  @Test
  @DisplayName("Host header with port is normalized to host only")
  @TracesRequirement("REQ-SHORT-007")
  void hostHeaderWithPortIsNormalized() {
    when(urlCache.lookup(TEST_ID)).thenReturn(CacheLookup.hit(new CachedUrlValue(TEST_URL, null)));

    assertThat(service.getOriginalUrl("localhost:8080", TEST_ID)).isEqualTo(TEST_URL);
  }

  @Test
  @DisplayName("Shorten binds a link to the caller's own active verified domain")
  @TracesRequirement("REQ-SHORT-003")
  void shortenBindsToOwnedActiveDomain() {
    String userId = "user123";
    when(customDomainRepository.findByHost("links.example.com"))
        .thenReturn(
            Optional.of(
                new CustomDomain(
                    "links.example.com",
                    userId,
                    DomainStatus.ACTIVE,
                    "url-shortener-verify=deadbeef",
                    Instant.now())));

    ShortUrl result = service.shorten(TEST_URL, null, userId, (Instant) null, "Links.Example.COM.");

    assertThat(result.domain()).isEqualTo("links.example.com");
    verify(urlRepository).save(argThat(saved -> "links.example.com".equals(saved.domain())));
  }

  @Test
  @DisplayName("Shorten with a custom alias binds the vanity link to the domain too")
  @TracesRequirement("REQ-SHORT-002")
  void shortenWithAliasBindsDomain() {
    String userId = "user123";
    when(urlIdGenerator.generateId("my-alias", userId)).thenReturn("my-alias");
    when(customDomainRepository.findByHost("links.example.com"))
        .thenReturn(
            Optional.of(
                new CustomDomain(
                    "links.example.com",
                    userId,
                    DomainStatus.ACTIVE,
                    "url-shortener-verify=deadbeef",
                    Instant.now())));

    ShortUrl result =
        service.shorten(TEST_URL, "my-alias", userId, (Instant) null, "links.example.com");

    assertThat(result.id()).isEqualTo("my-alias");
    assertThat(result.domain()).isEqualTo("links.example.com");
  }

  @Test
  @DisplayName("Shorten rejects a domain owned by another user with 403")
  @TracesRequirement("REQ-SHORT-003")
  void shortenRejectsDomainOwnedByAnotherUser() {
    when(customDomainRepository.findByHost("links.example.com"))
        .thenReturn(
            Optional.of(
                new CustomDomain(
                    "links.example.com",
                    "other-user",
                    DomainStatus.ACTIVE,
                    "url-shortener-verify=deadbeef",
                    Instant.now())));

    assertThatThrownBy(
            () -> service.shorten(TEST_URL, null, "user123", (Instant) null, "links.example.com"))
        .isInstanceOf(ForbiddenException.class);
    verify(urlRepository, never()).save(any(ShortUrl.class));
  }

  @Test
  @DisplayName("Shorten rejects an unclaimed domain with 400")
  @TracesRequirement("REQ-SHORT-003")
  void shortenRejectsUnclaimedDomain() {
    when(customDomainRepository.findByHost("unclaimed.example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.shorten(TEST_URL, null, "user123", (Instant) null, "unclaimed.example.com"))
        .isInstanceOf(InvalidDomainException.class);
    verify(urlRepository, never()).save(any(ShortUrl.class));
  }

  @Test
  @DisplayName("Shorten rejects a not-yet-verified domain with 400")
  @TracesRequirement("REQ-SHORT-003")
  void shortenRejectsUnverifiedDomain() {
    String userId = "user123";
    when(customDomainRepository.findByHost("links.example.com"))
        .thenReturn(
            Optional.of(
                new CustomDomain(
                    "links.example.com",
                    userId,
                    DomainStatus.PENDING,
                    "url-shortener-verify=deadbeef",
                    Instant.now())));

    assertThatThrownBy(
            () -> service.shorten(TEST_URL, null, userId, (Long) null, "links.example.com"))
        .isInstanceOf(DomainNotVerifiedException.class);
    verify(urlRepository, never()).save(any(ShortUrl.class));
  }

  @Test
  @DisplayName("Shorten requires authentication to bind a custom domain")
  @TracesRequirement("REQ-SHORT-003")
  void shortenRequiresAuthForDomain() {
    assertThatThrownBy(
            () -> service.shorten(TEST_URL, null, null, (Long) null, "links.example.com"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Authentication required");
    verify(customDomainRepository, never()).findByHost(anyString());
  }

  @Test
  @DisplayName("Blank domain keeps the link on the default host")
  void shortenWithBlankDomainStaysDefaultHost() {
    ShortUrl result = service.shorten(TEST_URL, null, "user123", (Long) null, "  ");

    assertThat(result.domain()).isNull();
    verify(customDomainRepository, never()).findByHost(anyString());
  }
}
