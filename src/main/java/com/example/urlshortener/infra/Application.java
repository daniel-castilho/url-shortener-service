package com.example.urlshortener.infra;

import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.core.ports.outgoing.IdGeneratorPort;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import com.example.urlshortener.core.service.UrlShortenerService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public UrlShortenerService urlShortenerService(UrlRepositoryPort urlRepository, IdGeneratorPort idGenerator,
            com.example.urlshortener.core.ports.outgoing.UrlCachePort urlCache) {
        return new UrlShortenerService(urlRepository, idGenerator, urlCache);
    }
}
