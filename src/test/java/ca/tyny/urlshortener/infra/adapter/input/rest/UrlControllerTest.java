package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.AnalyticsPort;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import ca.tyny.urlshortener.config.WithMockSecurity;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
@WithMockSecurity
@DisplayName("UrlController Tests")
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ca.tyny.urlshortener.infra.security.JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ca.tyny.urlshortener.core.ports.incoming.ShortenUrlUseCase shortenUrlUseCase;

    @MockitoBean
    private ca.tyny.urlshortener.core.ports.incoming.GetUrlUseCase getUrlUseCase;

    @MockitoBean
    private AnalyticsPort analyticsPort;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    @MockitoBean
    private ca.tyny.urlshortener.infra.observability.MetricsService metricsService;

    @MockitoBean
    private UserRepositoryPort userRepository;

    @MockitoBean
    private ClientAddressResolver clientAddressResolver;

    @MockitoBean
    private ca.tyny.urlshortener.infra.config.properties.ShortenerProperties shortenerProperties;

    private static final String TEST_URL = "https://www.example.com/very/long/url";
    private static final String TEST_ID = "abc123";
    private static final long MAX_TTL_SECONDS = 31_536_000L;

    @org.junit.jupiter.api.BeforeEach
    void setUpRateLimitAllow() {
        org.mockito.Mockito.when(clientAddressResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("127.0.0.1");
        org.mockito.Mockito.when(rateLimiter.tryAcquire(any(), anyString()))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(100));
        org.mockito.Mockito.when(shortenerProperties.maxTtlSeconds()).thenReturn(MAX_TTL_SECONDS);
    }

    @Test
    @DisplayName("POST /api/v1/urls should shorten URL successfully (Anonymous)")
    void shouldShortenUrl() throws Exception {
        // Given
        ShortenRequest request = new ShortenRequest(TEST_URL, null);
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());

        // Expect shorten called with null customAlias, null userId (anonymous) and no expiry
        when(shortenUrlUseCase.shorten(eq(TEST_URL), isNull(), isNull(), isNull())).thenReturn(shortUrl);

        // When/Then
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TEST_ID))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost/" + TEST_ID));

        verify(shortenUrlUseCase).shorten(eq(TEST_URL), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("POST /api/v1/urls should handle custom alias")
    void shouldHandleCustomAlias() throws Exception {
        // Given
        String customAlias = "my-alias";
        ShortenRequest request = new ShortenRequest(TEST_URL, customAlias);
        ShortUrl shortUrl = new ShortUrl(customAlias, TEST_URL, LocalDateTime.now());

        // Note: In this test with TestSecurityConfig, user is anonymous, so userId is
        // null.
        when(shortenUrlUseCase.shorten(eq(TEST_URL), eq(customAlias), isNull(), isNull())).thenReturn(shortUrl);

        // When/Then
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customAlias));

        verify(shortenUrlUseCase).shorten(eq(TEST_URL), eq(customAlias), isNull(), isNull());
    }

    @Test
    @DisplayName("GET /{id} should redirect to original URL")
    void shouldRedirectToOriginalUrl() throws Exception {
        // Given
        when(getUrlUseCase.getOriginalUrl(TEST_ID)).thenReturn(TEST_URL);

        // When/Then
        mockMvc.perform(get("/" + TEST_ID))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", TEST_URL));

        verify(getUrlUseCase).getOriginalUrl(TEST_ID);
        verify(analyticsPort).track(any());
    }

    @Test
    @DisplayName("GET /{id} should return 404 when URL not found")
    void shouldReturn404WhenUrlNotFound() throws Exception {
        // Given
        when(getUrlUseCase.getOriginalUrl(TEST_ID))
                .thenThrow(new UrlNotFoundException(TEST_ID));

        // When/Then
        mockMvc.perform(get("/" + TEST_ID))
                .andExpect(status().isNotFound());

        verify(getUrlUseCase).getOriginalUrl(TEST_ID);
        verify(analyticsPort, never()).track(any());
    }

    @Test
    @DisplayName("GET /{id} should return 429 with Retry-After when redirect limit exceeded")
    void shouldReturn429WithRetryAfterWhenRedirectLimited() throws Exception {
        when(rateLimiter.tryAcquire(eq(ca.tyny.urlshortener.core.ports.outgoing.RateLimitScope.REDIRECT),
                anyString()))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.block(30));

        mockMvc.perform(get("/" + TEST_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(header().string("RateLimit-Remaining", "0"));

        verify(getUrlUseCase, never()).getOriginalUrl(anyString());
        verify(analyticsPort, never()).track(any());
    }

    @Test
    @DisplayName("POST /api/v1/urls should return 429 when rate limit exceeded")
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        // Given
        ShortenRequest request = new ShortenRequest(TEST_URL, null);
        when(rateLimiter.tryAcquire(any(), anyString()))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.block(30));

        // When/Then
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());

        verify(shortenUrlUseCase, never()).shorten(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/urls should return 409 when alias already exists")
    void shouldReturn409WhenAliasAlreadyExists() throws Exception {
        // Given
        String customAlias = "existing-alias";
        ShortenRequest request = new ShortenRequest(TEST_URL, customAlias);
        when(shortenUrlUseCase.shorten(eq(TEST_URL), eq(customAlias), isNull(), isNull()))
                .thenThrow(new ca.tyny.urlshortener.core.exception.AliasAlreadyExistsException(customAlias));

        // When/Then
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Alias Already Exists"));
    }

    @Test
    @DisplayName("POST /api/v1/urls should pass a resolved expiry for a valid ttlSeconds")
    void shouldPassResolvedExpiryForValidTtl() throws Exception {
        // Given
        ShortUrl shortUrl = new ShortUrl(TEST_ID, TEST_URL, LocalDateTime.now());
        when(shortenUrlUseCase.shorten(eq(TEST_URL), isNull(), isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(shortUrl);
        String body = "{\"originalUrl\":\"" + TEST_URL + "\",\"ttlSeconds\":60}";

        // When/Then
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<java.time.Instant> expiresAt =
                org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(shortenUrlUseCase).shorten(eq(TEST_URL), isNull(), isNull(), expiresAt.capture());
        assertThatWithinRoughly(expiresAt.getValue(), java.time.Instant.now().plusSeconds(60), 30);
    }

    @Test
    @DisplayName("POST /api/v1/urls should reject ttlSeconds over the server cap with 400")
    void shouldRejectTtlOverCap() throws Exception {
        String body = "{\"originalUrl\":\"" + TEST_URL + "\",\"ttlSeconds\":" + (MAX_TTL_SECONDS + 1) + "}";

        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid Expiry"));

        verify(shortenUrlUseCase, never()).shorten(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/urls should reject non-positive ttlSeconds with 400")
    void shouldRejectNonPositiveTtl() throws Exception {
        String body = "{\"originalUrl\":\"" + TEST_URL + "\",\"ttlSeconds\":0}";

        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verify(shortenUrlUseCase, never()).shorten(anyString(), any(), any(), any());
    }

    private static void assertThatWithinRoughly(java.time.Instant actual, java.time.Instant expected,
            long toleranceSecs) {
        long delta = java.time.Duration.between(actual, expected).abs().toSeconds();
        org.assertj.core.api.Assertions.assertThat(delta).isLessThanOrEqualTo(toleranceSecs);
    }
}
