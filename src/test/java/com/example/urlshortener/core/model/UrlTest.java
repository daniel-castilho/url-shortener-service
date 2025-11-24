package com.example.urlshortener.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Url Value Object Tests")
class UrlTest {

    @Test
    @DisplayName("Should create valid HTTP URL")
    void shouldCreateValidHttpUrl() {
        // Given
        String validUrl = "http://example.com";

        // When
        Url url = new Url(validUrl);

        // Then
        assertThat(url.value()).isEqualTo(validUrl);
    }

    @Test
    @DisplayName("Should create valid HTTPS URL")
    void shouldCreateValidHttpsUrl() {
        // Given
        String validUrl = "https://example.com/path?param=value";

        // When
        Url url = new Url(validUrl);

        // Then
        assertThat(url.value()).isEqualTo(validUrl);
    }

    @Test
    @DisplayName("Should trim whitespace")
    void shouldTrimWhitespace() {
        // Given
        String urlWithSpaces = "  https://example.com  ";

        // When
        Url url = new Url(urlWithSpaces);

        // Then
        assertThat(url.value()).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("Should reject null URL")
    void shouldRejectNullUrl() {
        assertThatThrownBy(() -> new Url(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("URL cannot be null");
    }

    @Test
    @DisplayName("Should reject empty URL")
    void shouldRejectEmptyUrl() {
        assertThatThrownBy(() -> new Url(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL cannot be empty");
    }

    @Test
    @DisplayName("Should reject blank URL")
    void shouldRejectBlankUrl() {
        assertThatThrownBy(() -> new Url("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL cannot be empty");
    }

    @Test
    @DisplayName("Should reject invalid URL format")
    void shouldRejectInvalidUrlFormat() {
        assertThatThrownBy(() -> new Url("not-a-valid-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid URL format");
    }

    @Test
    @DisplayName("Should reject URL without protocol")
    void shouldRejectUrlWithoutProtocol() {
        assertThatThrownBy(() -> new Url("example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid URL format");
    }
}
