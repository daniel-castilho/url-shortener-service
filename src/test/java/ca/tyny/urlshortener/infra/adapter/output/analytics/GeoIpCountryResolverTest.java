package ca.tyny.urlshortener.infra.adapter.output.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeoIpCountryResolverTest {

  @Mock private DatabaseReader reader;

  @Mock private CountryResponse countryResponse;

  @Test
  @DisplayName("Returns the country code for a public IP when enabled")
  void resolvesPublicIp() throws Exception {
    Country country = mock(Country.class);
    when(country.getIsoCode()).thenReturn("BR");
    when(countryResponse.getCountry()).thenReturn(country);
    when(reader.country(any(InetAddress.class))).thenReturn(countryResponse);
    GeoIpCountryResolver resolver = new GeoIpCountryResolver(true, reader);

    assertThat(resolver.countryForIp("203.0.113.42")).isEqualTo("BR");
  }

  @Test
  @DisplayName("Disabled resolver never returns a country")
  void disabledResolverReturnsNull() {
    GeoIpCountryResolver resolver = GeoIpCountryResolver.disabled();

    assertThat(resolver.countryForIp("203.0.113.42")).isNull();
  }

  @Test
  @DisplayName("Private/internal IPs are never looked up")
  void skipsPrivateIps() {
    GeoIpCountryResolver resolver = new GeoIpCountryResolver(true, reader);

    assertThat(resolver.countryForIp("10.0.0.5")).isNull();
    assertThat(resolver.countryForIp("127.0.0.1")).isNull();
    assertThat(resolver.countryForIp("192.168.1.10")).isNull();
    assertThat(resolver.countryForIp("::1")).isNull();
  }

  @Test
  @DisplayName("Null and blank IPs return null")
  void nullAndBlankIps() {
    GeoIpCountryResolver resolver = new GeoIpCountryResolver(true, reader);

    assertThat(resolver.countryForIp(null)).isNull();
    assertThat(resolver.countryForIp("  ")).isNull();
  }

  @Test
  @DisplayName("Reader failures are swallowed (best-effort)")
  void readerFailureIsSwallowed() throws Exception {
    when(reader.country(any(InetAddress.class))).thenThrow(new RuntimeException("lookup exploded"));
    GeoIpCountryResolver resolver = new GeoIpCountryResolver(true, reader);

    assertThat(resolver.countryForIp("203.0.113.42")).isNull();
  }
}
