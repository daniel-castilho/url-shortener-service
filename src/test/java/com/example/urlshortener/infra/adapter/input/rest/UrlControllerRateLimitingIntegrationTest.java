package com.example.urlshortener.infra.adapter.input.rest;

import com.example.urlshortener.core.ports.outgoing.AnalyticsPort;
import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.urlshortener.config.WithMockSecurity;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
@WithMockSecurity
class UrlControllerRateLimitingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase shortenUrlUseCase;

    @MockitoBean
    private com.example.urlshortener.core.ports.incoming.GetUrlUseCase getUrlUseCase;

    @MockitoBean
    private AnalyticsPort analyticsPort;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    @MockitoBean
    private com.example.urlshortener.infra.security.JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private com.example.urlshortener.infra.observability.MetricsService metricsService;

    @MockitoBean
    private com.example.urlshortener.core.service.UserService userService;

    @MockitoBean
    private com.example.urlshortener.core.ports.outgoing.UserRepositoryPort userRepository;

    @BeforeEach
    void setUp() {
        when(shortenUrlUseCase.shorten(anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.example.urlshortener.core.model.ShortUrl("abc123", "https://example.com",
                        java.time.LocalDateTime.now(), null));
    }

    @Test
    void whenLimitExceeded_thenReturns429() throws Exception {
        // First request allowed
        when(rateLimiter.isAllowed("127.0.0.1")).thenReturn(true);
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com\"}")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .andExpect(status().isOk());

        // Second request exceeds limit
        when(rateLimiter.isAllowed("127.0.0.1")).thenReturn(false);
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com\"}")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .andExpect(status().isTooManyRequests());
    }
}
