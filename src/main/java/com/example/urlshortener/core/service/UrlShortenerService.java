package com.example.urlshortener.core.service;

import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.ports.incoming.GetUrlUseCase;
import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.core.ports.outgoing.IdGeneratorPort;
import com.example.urlshortener.core.ports.outgoing.UrlCachePort;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

public class UrlShortenerService implements ShortenUrlUseCase, GetUrlUseCase {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

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
        String id = idGenerator.generateId();
        ShortUrl shortUrl = new ShortUrl(id, originalUrl, LocalDateTime.now());
        urlRepository.save(shortUrl);
        // We could populate cache here too, but let's stick to lazy loading for now or
        // consistent write
        // urlCache.put(id, originalUrl);
        return shortUrl;
    }

    @Override
    public String getOriginalUrl(String id) {
        // 1. Check Cache
        String cachedUrl = urlCache.get(id);
        if (cachedUrl != null) {
            log.info("Cache Hit for ID: {}", id);
            return cachedUrl;
        }

        // 2. Check Database
        log.info("Cache Miss for ID: {}. Fetching from DB...", id);
        return urlRepository.findById(id)
                .map(shortUrl -> {
                    // 3. Populate Cache
                    urlCache.put(id, shortUrl.originalUrl());
                    return shortUrl.originalUrl();
                })
                .orElseThrow(() -> new com.example.urlshortener.core.exception.UrlNotFoundException(id));
    }
}
