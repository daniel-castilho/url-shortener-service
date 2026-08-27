package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.config.WithMockSecurity;
import ca.tyny.urlshortener.core.ports.outgoing.AnalyticsPort;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
@WithMockSecurity
class UrlControllerRateLimitingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ca.tyny.urlshortener.core.ports.incoming.ShortenUrlUseCase shortenUrlUseCase;

    @MockitoBean
    private ca.tyny.urlshortener.core.ports.incoming.GetUrlUseCase getUrlUseCase;

    @MockitoBean
    private AnalyticsPort analyticsPort;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    @MockitoBean
    private ca.tyny.urlshortener.infra.security.JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ca.tyny.urlshortener.infra.observability.MetricsService metricsService;

    @MockitoBean
    private UserRepositoryPort userRepository;

    @MockitoBean
    private ClientAddressResolver clientAddressResolver;

    @MockitoBean
    private ca.tyny.urlshortener.infra.config.properties.ShortenerProperties shortenerProperties;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(clientAddressResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("127.0.0.1");
        org.mockito.Mockito.when(shortenerProperties.maxTtlSeconds()).thenReturn(31_536_000L);
        when(shortenUrlUseCase.shorten(anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new ca.tyny.urlshortener.core.model.ShortUrl("abc123", "https://example.com",
                        java.time.LocalDateTime.now(), null));
    }

    @Test
    void whenLimitExceeded_thenReturns429() throws Exception {
        // First request allowed
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(10));
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com\"}")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk());

        // Second request exceeds limit
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.block(42));
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com\"}")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(
                        result -> org.junit.jupiter.api.Assertions.assertEquals("42",
                                result.getResponse().getHeader("Retry-After")));
    }

    @Test
    void whenRedirectLimitExceeded_thenReturns429BeforeLookup() throws Exception {
        when(rateLimiter.tryAcquire(ca.tyny.urlshortener.core.ports.outgoing.RateLimitScope.REDIRECT,
                "127.0.0.1"))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.block(15));

        mockMvc.perform(get("/whatever").with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertEquals("15",
                        result.getResponse().getHeader("Retry-After")));

        org.mockito.Mockito.verifyNoInteractions(getUrlUseCase);
    }
}
