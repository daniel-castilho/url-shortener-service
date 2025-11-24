package com.example.urlshortener.infra.adapter.input.rest;

import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import com.example.urlshortener.core.ports.incoming.GetUrlUseCase;
import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.core.ports.outgoing.AnalyticsPort;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
class UrlControllerRateLimitingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortenUrlUseCase shortenUrlUseCase;

    @MockitoBean
    private GetUrlUseCase getUrlUseCase;

    @MockitoBean
    private AnalyticsPort analyticsPort;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    @MockitoBean
    private RedissonClient redissonClient; // not used directly but required for context

    @MockitoBean
    private com.example.urlshortener.infra.observability.MetricsService metricsService;

    @BeforeEach
    void setUp() {
        when(shortenUrlUseCase.shorten(anyString()))
                .thenReturn(new com.example.urlshortener.core.model.ShortUrl("abc123", "https://example.com",
                        java.time.LocalDateTime.now()));
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
