package com.example.urlshortener.infra.config;

import com.example.urlshortener.core.idgeneration.UrlIdGenerator;
import com.example.urlshortener.core.ports.outgoing.MetricsPort;
import com.example.urlshortener.core.ports.outgoing.UrlCachePort;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import com.example.urlshortener.core.ports.outgoing.UserRepositoryPort;
import com.example.urlshortener.core.service.QuotaService;
import com.example.urlshortener.core.service.UrlShortenerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceConfig {

    @Bean
    public UrlShortenerService urlShortenerService(UrlRepositoryPort urlRepository,
            UrlCachePort urlCache,
            MetricsPort metrics,
            UrlIdGenerator urlIdGenerator,
            QuotaService quotaService,
            UserRepositoryPort userRepository) {
        return new UrlShortenerService(urlRepository, urlCache, metrics, urlIdGenerator, quotaService, userRepository);
    }
}
