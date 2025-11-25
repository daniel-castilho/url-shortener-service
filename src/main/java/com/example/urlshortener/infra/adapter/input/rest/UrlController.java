package com.example.urlshortener.infra.adapter.input.rest;

import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.model.User;
import com.example.urlshortener.core.ports.incoming.GetUrlUseCase;
import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.core.ports.outgoing.AnalyticsPort;
import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import com.example.urlshortener.core.ports.outgoing.UserRepositoryPort;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenResponse;
import com.example.urlshortener.infra.observability.MetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "URL Shortener", description = "High-performance URL shortening and redirection API")
public class UrlController {

        private final ShortenUrlUseCase shortenUrlUseCase;
        private final GetUrlUseCase getUrlUseCase;
        private final AnalyticsPort analyticsPort;
        private final RateLimiterPort rateLimiter;
        private final HttpServletRequest request;
        private final MetricsService metricsService;
        private final UserRepositoryPort userRepository;

        public UrlController(ShortenUrlUseCase shortenUrlUseCase,
                        GetUrlUseCase getUrlUseCase,
                        AnalyticsPort analyticsPort,
                        RateLimiterPort rateLimiter,
                        HttpServletRequest request,
                        MetricsService metricsService,
                        UserRepositoryPort userRepository) {
                this.shortenUrlUseCase = shortenUrlUseCase;
                this.getUrlUseCase = getUrlUseCase;
                this.analyticsPort = analyticsPort;
                this.rateLimiter = rateLimiter;
                this.request = request;
                this.metricsService = metricsService;
                this.userRepository = userRepository;
        }

        @PostMapping("/api/v1/urls")
        @Operation(summary = "Shorten a URL", description = "Creates a short URL code. Supports anonymous usage and authenticated usage with custom aliases (vanity URLs).")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "URL successfully shortened", content = @Content(schema = @Schema(implementation = ShortenResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid URL or custom alias", content = @Content),
                        @ApiResponse(responseCode = "409", description = "Custom alias already exists", content = @Content),
                        @ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
        })
        public ResponseEntity<ShortenResponse> shorten(
                        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "URL to be shortened", required = true, content = @Content(schema = @Schema(implementation = ShortenRequest.class))) @jakarta.validation.Valid @RequestBody ShortenRequest request) {
                long startTime = System.currentTimeMillis();
                try {
                        String clientIp = this.request.getRemoteAddr();
                        if (!rateLimiter.isAllowed(clientIp)) {
                                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
                        }

                        String userId = null;
                        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                        if (authentication != null && authentication.isAuthenticated() &&
                                        !(authentication instanceof AnonymousAuthenticationToken)) {
                                String email = authentication.getName();
                                userId = userRepository.findByEmail(email)
                                                .map(User::id)
                                                .orElse(null);
                        }

                        ShortUrl shortUrl = shortenUrlUseCase.shorten(request.originalUrl(), request.customAlias(),
                                        userId);
                        String baseUrl = org.springframework.web.servlet.support.ServletUriComponentsBuilder
                                        .fromCurrentContextPath().build().toUriString();

                        metricsService.recordUrlShortened();
                        return ResponseEntity.ok(new ShortenResponse(shortUrl.id(), baseUrl + "/" + shortUrl.id()));
                } finally {
                        metricsService.recordShortenLatency(System.currentTimeMillis() - startTime);
                }
        }

        @GetMapping("/{id}")
        @Operation(summary = "Redirect to original URL", description = "Retrieves the original URL and redirects (HTTP 302).")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "302", description = "Redirect to original URL"),
                        @ApiResponse(responseCode = "404", description = "Short URL not found", content = @Content)
        })
        public ResponseEntity<Void> redirect(
                        @Parameter(description = "Short URL code (e.g., vE1GpYK)", required = true, example = "vE1GpYK") @PathVariable String id,
                        HttpServletRequest request) {
                long startTime = System.currentTimeMillis();
                try {
                        String originalUrl = getUrlUseCase.getOriginalUrl(id);
                        analyticsPort.track(new com.example.urlshortener.core.model.ClickEvent(
                                        id,
                                        java.time.LocalDateTime.now(),
                                        request.getHeader("User-Agent"),
                                        request.getRemoteAddr()));

                        metricsService.recordRedirect();
                        return ResponseEntity.status(HttpStatus.FOUND).location(java.net.URI.create(originalUrl))
                                        .build();
                } finally {
                        metricsService.recordRedirectLatency(System.currentTimeMillis() - startTime);
                }
        }
}
