package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.infra.config.properties.RateLimiterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressResolverTest {

    private ClientAddressResolver resolverWith(List<String> cidrs) {
        return new ClientAddressResolver(new RateLimiterProperties(
                true, 60, java.time.Duration.ofMinutes(1), 120,
                java.time.Duration.ofMinutes(1), "X-Forwarded-For", cidrs));
    }

    @Test
    @DisplayName("Uses remote address for untrusted peers even with a forwarded header")
    void untrustedPeerCannotSpoof() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("X-Forwarded-For", "8.8.8.8");

        assertThat(resolverWith(List.of("127.0.0.0/8", "::1/128")).resolve(request))
                .isEqualTo("203.0.113.50");
    }

    @Test
    @DisplayName("Trusts left-most forwarded entry from a trusted proxy")
    void trustedProxyForwardedEntryWins() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.23, 10.0.0.5");

        assertThat(resolverWith(List.of("10.0.0.0/8")).resolve(request))
                .isEqualTo("198.51.100.23");
    }

    @Test
    @DisplayName("Falls back to remote address when no forwarded header is present")
    void fallsBackToPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");

        assertThat(resolverWith(List.of("10.0.0.0/8")).resolve(request))
                .isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("Never trusts headers when no trusted CIDRs are configured")
    void noTrustedProxiesMeansPeerOnly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.7");
        request.addHeader("X-Forwarded-For", "8.8.8.8");

        assertThat(resolverWith(List.of()).resolve(request))
                .isEqualTo("192.0.2.7");
    }
}
