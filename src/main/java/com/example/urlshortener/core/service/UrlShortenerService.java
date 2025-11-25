package com.example.urlshortener.core.service;

import com.example.urlshortener.core.idgeneration.UrlIdGenerator;
import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.model.Url;
import com.example.urlshortener.core.ports.incoming.GetUrlUseCase;
import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.core.ports.outgoing.MetricsPort;
import com.example.urlshortener.core.ports.outgoing.UrlCachePort;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import com.example.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Objects;

public class UrlShortenerService implements ShortenUrlUseCase, GetUrlUseCase {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

    // Log message constants
    private static final String LOG_CACHE_HIT = "Cache Hit for ID: {}";
    private static final String LOG_CACHE_MISS = "Cache Miss for ID: {}. Fetching from DB...";

    private final UrlRepositoryPort urlRepository;
    private final UrlCachePort urlCache;
    private final MetricsPort metrics;
    private final UrlIdGenerator urlIdGenerator;
    private final QuotaService quotaService;
    private final UserRepositoryPort userRepository;
    private final com.example.urlshortener.core.validation.ReservedWordsValidator reservedWordsValidator;

    public UrlShortenerService(UrlRepositoryPort urlRepository,
            UrlCachePort urlCache,
            MetricsPort metrics,
            UrlIdGenerator urlIdGenerator,
            QuotaService quotaService,
            UserRepositoryPort userRepository,
            com.example.urlshortener.core.validation.ReservedWordsValidator reservedWordsValidator) {
        this.urlRepository = urlRepository;
        this.urlCache = urlCache;
        this.metrics = metrics;
        this.urlIdGenerator = urlIdGenerator;
        this.quotaService = quotaService;
        this.userRepository = userRepository;
        this.reservedWordsValidator = reservedWordsValidator;
    }

    @Override
    public ShortUrl shorten(String originalUrl, String customAlias, String userId) {
        // Input validation
        Objects.requireNonNull(originalUrl, "URL cannot be null");

        // Validate URL format using Value Object
        Url validatedUrl = new Url(originalUrl);

        // Check Quota if user is authenticated and custom alias is requested
        boolean isCustomAlias = false;
        if (customAlias != null && !customAlias.isBlank()) {
            // Validate reserved words
            reservedWordsValidator.validate(customAlias);

            if (userId != null) {
                userRepository.findById(userId).ifPresent(user -> {
                    quotaService.checkVanityUrlQuota(user, customAlias);
                });
                isCustomAlias = true;
            } else {
                // Anonymous users cannot create custom aliases (enforced by controller, but
                // good to check here)
                throw new IllegalArgumentException("Authentication required for custom aliases");
            }
        }

        // Delegate ID generation to the decoupled module
        String id = urlIdGenerator.generateId(customAlias, userId);

        ShortUrl shortUrl = new ShortUrl(id, validatedUrl.value(), LocalDateTime.now(), userId, isCustomAlias);
        urlRepository.save(shortUrl);

        // Increment usage if user is authenticated and custom alias was used
        if (userId != null && customAlias != null && !customAlias.isBlank()) {
            userRepository.findById(userId).ifPresent(quotaService::incrementVanityUrlUsage);
        }

        // Record metric
        metrics.recordUrlShortened();

        return shortUrl;
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
                .orElseThrow(() -> new com.example.urlshortener.core.exception.UrlNotFoundException(id));
    }
}
