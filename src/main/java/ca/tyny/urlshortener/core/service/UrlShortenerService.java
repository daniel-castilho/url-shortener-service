package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.CodeGenerationException;
import ca.tyny.urlshortener.core.exception.InvalidDestinationException;
import ca.tyny.urlshortener.core.exception.ShortCodeCollisionException;
import ca.tyny.urlshortener.core.exception.UrlExpiredException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.Url;
import ca.tyny.urlshortener.core.ports.incoming.GetUrlUseCase;
import ca.tyny.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.validation.ReservedWordsValidator;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

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

    public UrlShortenerService(UrlRepositoryPort urlRepository,
            UrlCachePort urlCache,
            MetricsPort metrics,
            UrlIdGenerator urlIdGenerator,
            Base62CodeGenerator base62CodeGenerator,
            QuotaService quotaService,
            UserRepositoryPort userRepository,
            ReservedWordsValidator reservedWordsValidator,
            UrlValidator urlValidator) {
        this.urlRepository = urlRepository;
        this.urlCache = urlCache;
        this.metrics = metrics;
        this.urlIdGenerator = urlIdGenerator;
        this.base62CodeGenerator = base62CodeGenerator;
        this.quotaService = quotaService;
        this.userRepository = userRepository;
        this.reservedWordsValidator = reservedWordsValidator;
        this.urlValidator = urlValidator;
    }

    @Override
    public ShortUrl shorten(String originalUrl, String customAlias, String userId, Instant expiresAt) {
        Objects.requireNonNull(originalUrl, "URL cannot be null");
        urlValidator.validate(originalUrl);

        Url validatedUrl = new Url(originalUrl);

        boolean isCustomAlias = false;
        if (customAlias != null && !customAlias.isBlank()) {
            reservedWordsValidator.validate(customAlias);

            if (userId != null) {
                userRepository.findById(userId).ifPresent(user -> {
                    quotaService.checkVanityUrlQuota(user, customAlias);
                });
                isCustomAlias = true;
            } else {
                throw new IllegalArgumentException("Authentication required for custom aliases");
            }
        }

        ShortUrl shortUrl;
        if (isCustomAlias) {
            String id = urlIdGenerator.generateId(customAlias, userId);
            shortUrl = new ShortUrl(id, validatedUrl.value(), LocalDateTime.now(), userId, true)
                    .withExpiresAt(expiresAt);
            urlRepository.save(shortUrl);
        } else {
            shortUrl = saveWithCollisionRetry(validatedUrl.value(), userId, expiresAt);
        }

        if (userId != null && customAlias != null && !customAlias.isBlank()) {
            userRepository.findById(userId).ifPresent(quotaService::incrementVanityUrlUsage);
        }

        metrics.recordUrlShortened();

        return shortUrl;
    }

    private ShortUrl saveWithCollisionRetry(String originalUrl, String userId, Instant expiresAt) {
        for (int attempt = 0; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            long startNs = System.nanoTime();
            String id = base62CodeGenerator.generate();
            metrics.recordIdGeneration(Duration.ofNanos(System.nanoTime() - startNs));
            ShortUrl candidate = new ShortUrl(id, originalUrl, LocalDateTime.now(), userId, false)
                    .withExpiresAt(expiresAt);
            try {
                urlRepository.save(candidate);
                return candidate;
            } catch (ShortCodeCollisionException e) {
                if (attempt < MAX_COLLISION_RETRIES) {
                    log.warn("Code collision for id={}, retrying (attempt {}/{})", id, attempt + 1, MAX_COLLISION_RETRIES);
                } else {
                    throw new CodeGenerationException(MAX_COLLISION_RETRIES + 1);
                }
            }
        }
        throw new CodeGenerationException(MAX_COLLISION_RETRIES + 1);
    }

    @Override
    public String getOriginalUrl(String id) {
        Objects.requireNonNull(id, "ID cannot be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("ID cannot be empty");
        }

        long startNs = System.nanoTime();

        CacheLookup lookup = urlCache.lookup(id);
        if (lookup.isHit()) {
            log.info(LOG_CACHE_HIT, id);
            metrics.recordCacheHit();
            metrics.recordUrlRetrieval(Duration.ofNanos(System.nanoTime() - startNs));
            if (lookup.value().isExpired(Instant.now())) {
                metrics.recordUrlExpired();
                throw new UrlExpiredException(id);
            }
            return lookup.value().originalUrl();
        }

        // Policy B: BLOOM_NEGATIVE is treated as a lightweight cache-miss and resolved by findById.
        // The Bloom filter short-circuits only the Redis get, not the MongoDB lookup.
        if (lookup.absence() == CacheLookup.Absence.BLOOM_NEGATIVE) {
            log.debug("Bloom filter negative for id={}, falling back to DB (Policy B)", id);
        } else {
            log.info(LOG_CACHE_MISS, id);
        }
        metrics.recordCacheMiss();

        ShortUrl shortUrl = urlRepository.findById(id)
                .orElseThrow(() -> new UrlNotFoundException(id));

        if (shortUrl.isArchived()) {
            metrics.recordUrlExpired(); // reuse expired metric for archived
            throw new UrlNotFoundException(id);
        }

        if (shortUrl.isExpired(Instant.now())) {
            metrics.recordUrlExpired();
            throw new UrlExpiredException(id);
        }

        urlCache.put(id, new CachedUrlValue(shortUrl.originalUrl(), shortUrl.expiresAt()));
        metrics.recordUrlRetrieval(Duration.ofNanos(System.nanoTime() - startNs));
        return shortUrl.originalUrl();
    }
}