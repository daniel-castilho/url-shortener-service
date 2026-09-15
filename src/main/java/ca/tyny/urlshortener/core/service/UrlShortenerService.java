package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.CodeGenerationException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidExpiryException;
import ca.tyny.urlshortener.core.exception.ShortCodeCollisionException;
import ca.tyny.urlshortener.core.exception.UrlExpiredException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.Url;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.incoming.GetUrlUseCase;
import ca.tyny.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.validation.DomainBindingValidator;
import ca.tyny.urlshortener.core.validation.Hostnames;
import ca.tyny.urlshortener.core.validation.ReservedWordsValidator;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UrlShortenerService implements ShortenUrlUseCase, GetUrlUseCase {

  private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

  private static final String LOG_CACHE_HIT = "Cache Hit for ID: {}";
  private static final String LOG_CACHE_MISS = "Cache Miss for ID: {}. Fetching from DB...";
  static final int MAX_COLLISION_RETRIES = 8;

  private final UrlRepositoryPort urlRepository;
  private final UrlCachePort urlCache;
  private final MetricsPort metrics;
  private final UrlIdGenerator urlIdGenerator;
  private final Base62CodeGenerator base62CodeGenerator;
  private final QuotaService quotaService;
  private final UserRepositoryPort userRepository;
  private final ReservedWordsValidator reservedWordsValidator;
  private final UrlValidator urlValidator;
  private final CustomDomainRegistryPort customDomainRegistry;
  private final CustomDomainRepositoryPort customDomainRepository;
  private final String defaultHost;
  private final Long maxTtlSeconds;

  public UrlShortenerService(
      UrlRepositoryPort urlRepository,
      UrlCachePort urlCache,
      MetricsPort metrics,
      UrlIdGenerator urlIdGenerator,
      Base62CodeGenerator base62CodeGenerator,
      QuotaService quotaService,
      UserRepositoryPort userRepository,
      ReservedWordsValidator reservedWordsValidator,
      UrlValidator urlValidator) {
    this(
        urlRepository,
        urlCache,
        metrics,
        urlIdGenerator,
        base62CodeGenerator,
        quotaService,
        userRepository,
        reservedWordsValidator,
        urlValidator,
        null,
        null,
        null,
        null);
  }

  public UrlShortenerService(
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
      String defaultHost) {
    this(
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
        null,
        defaultHost,
        null);
  }

  public UrlShortenerService(
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
      String defaultHost) {
    this(
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
        defaultHost,
        null);
  }

  public UrlShortenerService(
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
      String defaultHost,
      Long maxTtlSeconds) {
    this.urlRepository = urlRepository;
    this.urlCache = urlCache;
    this.metrics = metrics;
    this.urlIdGenerator = urlIdGenerator;
    this.base62CodeGenerator = base62CodeGenerator;
    this.quotaService = quotaService;
    this.userRepository = userRepository;
    this.reservedWordsValidator = reservedWordsValidator;
    this.urlValidator = urlValidator;
    this.customDomainRegistry = customDomainRegistry;
    this.customDomainRepository = customDomainRepository;
    this.defaultHost = defaultHost;
    this.maxTtlSeconds = maxTtlSeconds;
  }

  @Override
  public ShortUrl shorten(
      String originalUrl, String customAlias, String userId, Instant expiresAt, String domain) {
    Objects.requireNonNull(originalUrl, "URL cannot be null");

    // Write-path block check (ADR 0011 D4): only runs when a session is present (userId != null).
    // Anonymous shortening stays allowed — the block is per-account, not per-IP. The check precedes
    // any validation or persistence so a blocked account never writes nor counts quota.
    if (userId != null) {
      userRepository.findById(userId).ifPresent(this::assertNotBlocked);
    }

    urlValidator.validate(originalUrl);

    Url validatedUrl = new Url(originalUrl);

    boolean isCustomAlias = false;
    if (customAlias != null && !customAlias.isBlank()) {
      reservedWordsValidator.validate(customAlias);

      if (userId != null) {
        userRepository
            .findById(userId)
            .ifPresent(
                user -> {
                  quotaService.checkVanityUrlQuota(user, customAlias);
                });
        isCustomAlias = true;
      } else {
        throw new IllegalArgumentException("Authentication required for custom aliases");
      }
    }

    if (domain != null && !domain.isBlank() && customDomainRepository == null) {
      throw new IllegalArgumentException("Custom domains are not available");
    }
    String boundDomain =
        DomainBindingValidator.bindableDomain(customDomainRepository, userId, domain);

    ShortUrl shortUrl;
    if (isCustomAlias) {
      String id = urlIdGenerator.generateId(customAlias, userId);
      shortUrl =
          new ShortUrl(id, validatedUrl.value(), LocalDateTime.now(), userId, true)
              .withExpiresAt(expiresAt)
              .withDomain(boundDomain);
      urlRepository.save(shortUrl);
    } else {
      shortUrl = saveWithCollisionRetry(validatedUrl.value(), userId, expiresAt, boundDomain);
    }

    if (userId != null && customAlias != null && !customAlias.isBlank()) {
      userRepository.findById(userId).ifPresent(quotaService::incrementVanityUrlUsage);
    }

    metrics.recordUrlShortened();

    return shortUrl;
  }

  @Override
  public ShortUrl shorten(
      String originalUrl, String customAlias, String userId, Long ttlSeconds, String domain) {
    Instant expiresAt;
    if (ttlSeconds == null) {
      expiresAt = null;
    } else {
      if (maxTtlSeconds == null) {
        throw new InvalidExpiryException("ttlSeconds supplied but no TTL cap is configured");
      }
      expiresAt = ExpiryResolver.resolveExpiresAt(ttlSeconds, maxTtlSeconds);
    }
    return shorten(originalUrl, customAlias, userId, expiresAt, domain);
  }

  private ShortUrl saveWithCollisionRetry(
      String originalUrl, String userId, Instant expiresAt, String domain) {
    for (int attempt = 0; attempt <= MAX_COLLISION_RETRIES; attempt++) {
      long startNs = System.nanoTime();
      String id = base62CodeGenerator.generate();
      metrics.recordIdGeneration(Duration.ofNanos(System.nanoTime() - startNs));
      ShortUrl candidate =
          new ShortUrl(id, originalUrl, LocalDateTime.now(), userId, false)
              .withExpiresAt(expiresAt)
              .withDomain(domain);
      try {
        urlRepository.save(candidate);
        return candidate;
      } catch (ShortCodeCollisionException e) {
        if (attempt < MAX_COLLISION_RETRIES) {
          log.warn(
              "Code collision for id={}, retrying (attempt {}/{})",
              id,
              attempt + 1,
              MAX_COLLISION_RETRIES);
        } else {
          throw new CodeGenerationException(MAX_COLLISION_RETRIES + 1);
        }
      }
    }
    throw new CodeGenerationException(MAX_COLLISION_RETRIES + 1);
  }

  @Override
  public String getOriginalUrl(String host, String id) {
    Objects.requireNonNull(id, "ID cannot be null");
    if (id.isBlank()) {
      throw new IllegalArgumentException("ID cannot be empty");
    }

    String normalizedHost = Hostnames.fromHostHeader(host);
    if (normalizedHost == null || normalizedHost.isBlank()) {
      normalizedHost = defaultHost;
    }
    boolean onDefaultHost = normalizedHost.equalsIgnoreCase(defaultHost);
    boolean onActiveCustomHost =
        !onDefaultHost
            && customDomainRegistry != null
            && customDomainRegistry.isActiveHost(normalizedHost);

    // Strict mirror: an unknown host is neither the default host nor a verified custom
    // domain, so nothing is reachable under it. A custom host without a binding = 404.
    if (!onDefaultHost && !onActiveCustomHost) {
      log.info("Rejecting id={} on unbound host {}", id, normalizedHost);
      throw new UrlNotFoundException(id);
    }

    long startNs = System.nanoTime();

    CacheLookup lookup = urlCache.lookup(id);
    if (lookup.isHit()) {
      log.info(LOG_CACHE_HIT, id);
      metrics.recordCacheHit();
      metrics.recordUrlRetrieval(Duration.ofNanos(System.nanoTime() - startNs));
      CachedUrlValue cached = lookup.value();
      if (cached.isExpired(Instant.now())) {
        metrics.recordUrlExpired();
        throw new UrlExpiredException(id);
      }
      return serve(cached.originalUrl(), cached.domain(), onDefaultHost, normalizedHost, id);
    }

    // Policy B: BLOOM_NEGATIVE is treated as a lightweight cache-miss and resolved by findById.
    // The Bloom filter short-circuits only the Redis get, not the MongoDB lookup.
    if (lookup.absence() == CacheLookup.Absence.BLOOM_NEGATIVE) {
      log.debug("Bloom filter negative for id={}, falling back to DB (Policy B)", id);
    } else {
      log.info(LOG_CACHE_MISS, id);
    }
    metrics.recordCacheMiss();

    ShortUrl shortUrl = urlRepository.findById(id).orElseThrow(() -> new UrlNotFoundException(id));

    if (shortUrl.isArchived()) {
      metrics.recordUrlExpired(); // reuse expired metric for archived
      throw new UrlNotFoundException(id);
    }

    if (shortUrl.isExpired(Instant.now())) {
      metrics.recordUrlExpired();
      throw new UrlExpiredException(id);
    }

    String resolved =
        serve(shortUrl.originalUrl(), shortUrl.domain(), onDefaultHost, normalizedHost, id);
    urlCache.put(
        id, new CachedUrlValue(shortUrl.originalUrl(), shortUrl.expiresAt(), shortUrl.domain()));
    metrics.recordUrlRetrieval(Duration.ofNanos(System.nanoTime() - startNs));
    return resolved;
  }

  /**
   * Enforces the strict host mirror for a resolved link and returns the destination.
   *
   * @throws UrlNotFoundException when the link is not bound to this host (silent 404 — never leaks
   *     that a code exists on another host)
   */
  private String serve(
      String originalUrl, String domain, boolean onDefaultHost, String host, String id) {
    if (onDefaultHost) {
      if (domain != null) {
        throw new UrlNotFoundException(id);
      }
      return originalUrl;
    }
    if (domain == null || !domain.equals(host)) {
      throw new UrlNotFoundException(id);
    }
    return originalUrl;
  }

  /** Rejects a session belonging to a blocked account (ADR 0011 D4). */
  private void assertNotBlocked(User user) {
    if (user.blocked()) {
      throw new ForbiddenException("Account blocked.");
    }
  }
}
