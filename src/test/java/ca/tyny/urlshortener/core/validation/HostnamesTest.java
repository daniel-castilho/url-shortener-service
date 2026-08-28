package ca.tyny.urlshortener.core.validation;

import ca.tyny.urlshortener.core.exception.InvalidDomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Hostnames — normalization & validation unit tests")
class HostnamesTest {

    @Test
    @DisplayName("normalize lowercases, trims and strips trailing dots")
    void normalize_cleansHost() {
        assertThat(Hostnames.normalize("  LINKS.Example.COM. ")).isEqualTo("links.example.com");
        assertThat(Hostnames.normalize("example.com.")).isEqualTo("example.com");
        assertThat(Hostnames.normalize(null)).isNull();
    }

    @Test
    @DisplayName("validate accepts well-formed multi-label hosts")
    void validate_acceptsValidHosts() {
        Hostnames.validate("links.example.com");
        Hostnames.validate("a-b.example.co.uk");
        Hostnames.validate("example.com");
    }

    @Test
    @DisplayName("validate rejects single-label hosts")
    void validate_rejectsSingleLabel() {
        assertThatThrownBy(() -> Hostnames.validate("localhost"))
                .isInstanceOf(InvalidDomainException.class);
        assertThatThrownBy(() -> Hostnames.validate("service"))
                .isInstanceOf(InvalidDomainException.class);
    }

    @Test
    @DisplayName("validate rejects malformed hosts and IP literals")
    void validate_rejectsMalformed() {
        assertThatThrownBy(() -> Hostnames.validate("not a host"))
                .isInstanceOf(InvalidDomainException.class);
        assertThatThrownBy(() -> Hostnames.validate(""))
                .isInstanceOf(InvalidDomainException.class);
        assertThatThrownBy(() -> Hostnames.validate("a..b"))
                .isInstanceOf(InvalidDomainException.class);
        assertThatThrownBy(() -> Hostnames.validate("192.168.1.1"))
                .isInstanceOf(InvalidDomainException.class);
        assertThatThrownBy(() -> Hostnames.validate("a b.example.com"))
                .isInstanceOf(InvalidDomainException.class);
    }
}