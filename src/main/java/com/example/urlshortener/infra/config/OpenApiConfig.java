package com.example.urlshortener.infra.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("High-Performance URL Shortener API")
                        .version("1.0.0")
                        .description("""
                                Ultra-fast URL shortening service built with:
                                - Java 21 Virtual Threads
                                - Spring Boot 3.5.7 + Undertow
                                - MongoDB + Redis
                                - Bloom Filters & Multi-Level Caching

                                Designed to handle 100M+ writes/day and 1B+ reads/day.
                                """)
                        .contact(new Contact()
                                .name("API Support")
                                .url("https://github.com/seu-usuario/url-shortener")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development")));
    }
}
