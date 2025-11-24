package com.example.urlshortener.infra.config;

import org.hashids.Hashids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShortCodeConfig {

    @Value("${app.shortener.salt}")
    private String salt;

    @Bean
    public Hashids hashids() {
        // MinLength 7 guarantees that even ID "1" generates something like "a1b2c3d"
        return new Hashids(salt, 7);
    }
}
