package com.example.urlshortener.core.service;

import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.model.Url;
import com.example.urlshortener.core.ports.incoming.GetUrlUseCase;
import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.core.ports.outgoing.IdGeneratorPort;
import com.example.urlshortener.core.ports.outgoing.UrlCachePort;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
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
    private final IdGeneratorPort idGenerator;
    private final UrlCachePort urlCache;

    public UrlShortenerService(UrlRepositoryPort urlRepository, IdGeneratorPort idGenerator, UrlCachePort urlCache) {
        this.urlRepository = urlRepository;
        this.idGenerator = idGenerator;
        this.urlCache = urlCache;
    }

    @Override
    public ShortUrl shorten(String originalUrl) {
        // Input validation
        Objects.requireNonNull(originalUrl, "URL cannot be null");

        // Validate URL format using Value Object
        Url validatedUrl = new Url(originalUrl);

        String id = idGenerator.generateId();
        ShortUrl shortUrl = new ShortUrl(id, validatedUrl.value(), LocalDateTime.now());
        urlRepository.save(shortUrl);

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
            return cachedUrl;
        }

        // 2. Check Database
        log.info(LOG_CACHE_MISS, id);
        return urlRepository.findById(id)
                .map(shortUrl -> {
                    // 3. Populate Cache
                    urlCache.put(id, shortUrl.originalUrl());
                    return shortUrl.originalUrl();
                })
                .orElseThrow(() -> new com.example.urlshortener.core.exception.UrlNotFoundException(id));
    }
}
