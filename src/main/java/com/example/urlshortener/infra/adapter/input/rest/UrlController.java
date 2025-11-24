package com.example.urlshortener.infra.adapter.input.rest;

import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "URL Shortener", description = "High-performance URL shortening and redirection API")
public class UrlController {

    private final ShortenUrlUseCase shortenUrlUseCase;
    private final com.example.urlshortener.core.ports.incoming.GetUrlUseCase getUrlUseCase;
    private final com.example.urlshortener.core.ports.outgoing.AnalyticsPort analyticsPort;

    public UrlController(ShortenUrlUseCase shortenUrlUseCase,
            com.example.urlshortener.core.ports.incoming.GetUrlUseCase getUrlUseCase,
            com.example.urlshortener.core.ports.outgoing.AnalyticsPort analyticsPort) {
        this.shortenUrlUseCase = shortenUrlUseCase;
        this.getUrlUseCase = getUrlUseCase;
        this.analyticsPort = analyticsPort;
    }

    @PostMapping("/api/v1/urls")
    @Operation(summary = "Shorten a URL", description = "Creates a short URL code for the provided long URL. Uses Hashids encoding with Redis-backed ID generation (1000 IDs batched in memory).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "URL successfully shortened", content = @Content(schema = @Schema(implementation = ShortenResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid URL format", content = @Content)
    })
    public ResponseEntity<ShortenResponse> shorten(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "URL to be shortened", required = true, content = @Content(schema = @Schema(implementation = ShortenRequest.class))) @RequestBody ShortenRequest request) {
        ShortUrl shortUrl = shortenUrlUseCase.shorten(request.originalUrl());
        String baseUrl = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
                .build().toUriString();
        return ResponseEntity.ok(new ShortenResponse(shortUrl.id(), baseUrl + "/" + shortUrl.id()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    @Operation(summary = "Redirect to original URL", description = """
            Retrieves the original URL and redirects (HTTP 302).
            Uses multi-level caching: Caffeine (L1, 5s) → Redis (L2, 24h+jitter) → Cassandra.
            Click events are tracked asynchronously without blocking the redirect.
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "302", description = "Redirect to original URL"),
            @ApiResponse(responseCode = "404", description = "Short URL not found", content = @Content)
    })
    public ResponseEntity<Void> redirect(
            @Parameter(description = "Short URL code (e.g., vE1GpYK)", required = true, example = "vE1GpYK") @org.springframework.web.bind.annotation.PathVariable String id,
            jakarta.servlet.http.HttpServletRequest request) {
        String originalUrl = getUrlUseCase.getOriginalUrl(id);

        // Async Analytics Track
        analyticsPort.track(new com.example.urlshortener.core.model.ClickEvent(
                id,
                java.time.LocalDateTime.now(),
                request.getHeader("User-Agent"),
                request.getRemoteAddr()));

        return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                .location(java.net.URI.create(originalUrl))
                .build();
    }
}
