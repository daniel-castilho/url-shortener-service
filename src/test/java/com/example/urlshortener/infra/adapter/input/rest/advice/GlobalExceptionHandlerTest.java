package com.example.urlshortener.infra.adapter.input.rest.advice;

import com.example.urlshortener.core.exception.UrlNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.urlshortener.core.ports.incoming.GetUrlUseCase;
import com.example.urlshortener.core.ports.incoming.ShortenUrlUseCase;
import com.example.urlshortener.core.ports.outgoing.AnalyticsPort;
import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import com.example.urlshortener.infra.adapter.input.rest.UrlController;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlController.class)
@DisplayName("GlobalExceptionHandler Tests")
class GlobalExceptionHandlerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ShortenUrlUseCase shortenUrlUseCase;

        @MockitoBean
        private GetUrlUseCase getUrlUseCase;

        @MockitoBean
        private AnalyticsPort analyticsPort;

        @MockitoBean
        private RateLimiterPort rateLimiter;

        @MockitoBean
        private com.example.urlshortener.infra.observability.MetricsService metricsService;

        @Test
        @DisplayName("Should return 404 with error response when URL not found")
        void shouldReturn404WhenUrlNotFound() throws Exception {
                // Given
                when(rateLimiter.isAllowed(any())).thenReturn(true);
                String nonExistentId = "notfound";
                when(getUrlUseCase.getOriginalUrl(nonExistentId))
                                .thenThrow(new UrlNotFoundException(nonExistentId));

                // When/Then
                mockMvc.perform(get("/" + nonExistentId))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404))
                                .andExpect(jsonPath("$.error").value("URL Not Found"))
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("Should return 400 with validation errors for empty URL")
        void shouldReturn400ForEmptyUrl() throws Exception {
                // Given
                when(rateLimiter.isAllowed(any())).thenReturn(true);
                ShortenRequest request = new ShortenRequest("");

                // When/Then
                mockMvc.perform(post("/api/v1/urls")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400))
                                .andExpect(jsonPath("$.error").value("Validation Failed"))
                                .andExpect(jsonPath("$.validationErrors.originalUrl").exists());
        }

        @Test
        @DisplayName("Should return 400 with validation errors for invalid URL format")
        void shouldReturn400ForInvalidUrlFormat() throws Exception {
                // Given
                when(rateLimiter.isAllowed(any())).thenReturn(true);
                ShortenRequest request = new ShortenRequest("not-a-valid-url");

                // When/Then
                mockMvc.perform(post("/api/v1/urls")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400))
                                .andExpect(jsonPath("$.error").value("Validation Failed"))
                                .andExpect(jsonPath("$.validationErrors.originalUrl")
                                                .value("URL must start with http:// or https://"));
        }

        @Test
        @DisplayName("Should return 400 with validation errors for null URL")
        void shouldReturn400ForNullUrl() throws Exception {
                // Given
                when(rateLimiter.isAllowed(any())).thenReturn(true);
                String requestJson = "{}";

                // When/Then
                mockMvc.perform(post("/api/v1/urls")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400))
                                .andExpect(jsonPath("$.error").value("Validation Failed"))
                                .andExpect(jsonPath("$.validationErrors.originalUrl").exists());
        }

        @Test
        @DisplayName("Should return 400 for IllegalArgumentException")
        void shouldReturn400ForIllegalArgument() throws Exception {
                // Given
                when(rateLimiter.isAllowed(any())).thenReturn(true);
                ShortenRequest request = new ShortenRequest("https://example.com");
                when(shortenUrlUseCase.shorten(any()))
                                .thenThrow(new IllegalArgumentException("Invalid input"));

                // When/Then
                mockMvc.perform(post("/api/v1/urls")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400))
                                .andExpect(jsonPath("$.error").value("Invalid Request"))
                                .andExpect(jsonPath("$.message").value("Invalid input"));
        }

        @Test
        @DisplayName("Should return 500 for unexpected exceptions")
        void shouldReturn500ForUnexpectedException() throws Exception {
                // Given
                when(rateLimiter.isAllowed(any())).thenReturn(true);
                ShortenRequest request = new ShortenRequest("https://example.com");
                when(shortenUrlUseCase.shorten(any()))
                                .thenThrow(new RuntimeException("Unexpected error"));

                // When/Then
                mockMvc.perform(post("/api/v1/urls")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isInternalServerError())
                                .andExpect(jsonPath("$.status").value(500))
                                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                                .andExpect(jsonPath("$.message")
                                                .value("An unexpected error occurred. Please try again later."));
        }
}
