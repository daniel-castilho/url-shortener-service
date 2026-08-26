package ca.tyny.urlshortener.infra.adapter.output.validation;

import ca.tyny.urlshortener.core.exception.InvalidDestinationException;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import ca.tyny.urlshortener.infra.config.properties.UrlValidationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultUrlValidator Tests")
class DefaultUrlValidatorTest {

    private UrlValidator createValidator(boolean allowHttp, boolean blockPrivateIps, int dnsTimeoutMs) {
        return new DefaultUrlValidator(new UrlValidationProperties(allowHttp, 2000, blockPrivateIps, 300));
    }

    @Test
    @DisplayName("Rejects HTTP when allowHttp is false")
    void rejectsHttpWhenDisabled() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("http://example.com"))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("HTTP URLs are not allowed");
    }

    @Test
    @DisplayName("Rejects URL without scheme")
    void rejectsUrlWithoutScheme() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("example.com"))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("must use http:// or https://");
    }

    @Test
    @DisplayName("Rejects malformed URL format")
    void rejectsInvalidFormat() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("not-a-url"))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("must use http:// or https://");
    }

    @Test
    @DisplayName("Rejects invalid host format (spaces)")
    void rejectsInvalidHost() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("https://invalid host.com"))
                .isInstanceOf(InvalidDestinationException.class);
    }

    @Test
    @DisplayName("Rejects URL with userinfo (credentials)")
    void rejectsUserInfo() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("https://user:pass@example.com"))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("user credentials");
    }

    @Test
    @DisplayName("Rejects URL with userinfo (email-like)")
    void rejectsUserInfoEmail() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("https://user@example.com"))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("user credentials");
    }

    @Test
    @DisplayName("Valid HTTPS URL structure passes format validation")
    void allowsValidHttpsStructure() {
        UrlValidator validator = createValidator(false, true, 2000);

        // The URL structure is valid; DNS resolution is attempted but not mocked here
        // The validator will attempt DNS resolution which may succeed or fail
        // We just verify format validation passes
        var result = new DefaultUrlValidator(new UrlValidationProperties(false, 2000, true, 300)).doValidate("https://example.com");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("Allows HTTP when allowHttp is true")
    void allowsHttpWhenEnabled() {
        UrlValidator validator = createValidator(true, true, 2000);

        var result = new DefaultUrlValidator(new UrlValidationProperties(true, 2000, true, 300)).doValidate("http://example.com");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("Rejects invalid host format (special chars)")
    void rejectsInvalidHostSpecialChars() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("https://invalid@host.com"))
                .isInstanceOf(InvalidDestinationException.class);
    }

    @Test
    @DisplayName("Rejects invalid scheme")
    void rejectsInvalidScheme() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("ftp://example.com"))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("http:// or https://");
    }

    @Test
    @DisplayName("Rejects empty host")
    void rejectsEmptyHost() {
        UrlValidator validator = createValidator(false, true, 2000);

        assertThatThrownBy(() -> validator.validate("https://"))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("authority");
    }
}