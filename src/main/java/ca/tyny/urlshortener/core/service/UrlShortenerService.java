package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.CodeGenerationException;
import ca.tyny.urlshortener.core.idgeneration.Base62CodeGenerator;
import ca.tyny.urlshortener.core.idgeneration.UrlIdGenerator;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.Url;
import ca.tyny.urlshortener.core.ports.incoming.GetUrlUseCase;
import ca.tyny.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final ca.tyny.urlshortener.core.validation.ReservedWordsValidator reservedWordsValidator;

    public UrlShortenerService(UrlRepositoryPort urlRepository,
            UrlCachePort urlCache,
            MetricsPort metrics,
            UrlIdGenerator urlIdGenerator,
            Base62CodeGenerator base62CodeGenerator,
            QuotaService quotaService,
            UserRepositoryPort userRepository,
            ca.tyny.urlshortener.core.validation.ReservedWordsValidator reservedWordsValidator) {
        this.urlRepository = urlRepository;
        this.urlCache = urlCache;
        this.metrics = metrics;
        this.urlIdGenerator = urlIdGenerator;
        this.base62CodeGenerator = base62CodeGenerator;
        this.quotaService = quotaService;
        this.userRepository = userRepository;
        this.reservedWordsValidator = reservedWordsValidator;
    }

    @Override
    public ShortUrl shorten(String originalUrl, String customAlias, String userId) {
        Objects.requireNonNull(originalUrl, "URL cannot be null");

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
            shortUrl = new ShortUrl(id, validatedUrl.value(), LocalDateTime.now(), userId, true);
            urlRepository.save(shortUrl);
        } else {
            shortUrl = saveWithCollisionRetry(validatedUrl.value(), userId);
        }

        if (userId != null && customAlias != null && !customAlias.isBlank()) {
            userRepository.findById(userId).ifPresent(quotaService::incrementVanityUrlUsage);
        }

        metrics.recordUrlShortened();

        return shortUrl;
    }

    private ShortUrl saveWithCollisionRetry(String originalUrl, String userId) {
        for (int attempt = 0; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            String id = base62CodeGenerator.generate();
            ShortUrl candidate = new ShortUrl(id, originalUrl, LocalDateTime.now(), userId, false);
            try {
                urlRepository.save(candidate);
                return candidate;
            } catch (RuntimeException e) {
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
        // Input validation
        Objects.requireNonNull(id, "ID cannot be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("ID cannot be empty");
        }

        // 1. Check Cache
        String cachedUrl = urlCache.get(id);
        if (cachedUrl != null) {
            log.info(LOG_CACHE_HIT, id);
            metrics.recordCacheHit();
            return cachedUrl;
        }

        // 2. Check Database
        log.info(LOG_CACHE_MISS, id);
        metrics.recordCacheMiss();

        return urlRepository.findById(id)
                .map(shortUrl -> {
                    // 3. Populate Cache
                    urlCache.put(id, shortUrl.originalUrl());
                    return shortUrl.originalUrl();
                })
                .orElseThrow(() -> new ca.tyny.urlshortener.core.exception.UrlNotFoundException(id));
    }
}
