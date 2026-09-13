package ca.tyny.urlshortener.infra.adapter.output.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickEventDocument;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClickBatchWorkerMappingTest {

  private static final Instant CONSUMED_AT = Instant.parse("2026-08-26T13:00:00Z");

  @Test
  @DisplayName("Should map stream payload to document with parsed instant")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldMapPayloadToDocument() {
    Map<Object, Object> value = new HashMap<>();
    value.put("code", "abc123");
    value.put("ts", "2026-08-26T12:00:00Z");
    value.put("ua", "UA/2.0");
    value.put("ip", "198.51.100.9");
    value.put("ref", "https://ref.example.com/page");
    value.put("dev", "desktop");
    value.put("cc", "BR");

    ClickEventDocument doc = ClickBatchWorker.toDocument(value, CONSUMED_AT);

    assertThat(doc.getShortCode()).isEqualTo("abc123");
    assertThat(doc.getTimestamp()).isEqualTo(Instant.parse("2026-08-26T12:00:00Z"));
    assertThat(doc.getUserAgent()).isEqualTo("UA/2.0");
    assertThat(doc.getIp()).isEqualTo("198.51.100.9");
    assertThat(doc.getReferrer()).isEqualTo("https://ref.example.com/page");
    assertThat(doc.getDevice()).isEqualTo("desktop");
    assertThat(doc.getCountry()).isEqualTo("BR");
  }

  @Test
  @DisplayName("Should fall back to consumedAt on missing or malformed timestamp")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldFallbackTimestamp() {
    Map<Object, Object> malformed = new HashMap<>();
    malformed.put("code", "abc123");
    malformed.put("ts", "not-a-timestamp");

    ClickEventDocument docMalformed = ClickBatchWorker.toDocument(malformed, CONSUMED_AT);
    ClickEventDocument docMissing = ClickBatchWorker.toDocument(Map.of("code", "x"), CONSUMED_AT);

    assertThat(docMalformed.getTimestamp()).isEqualTo(CONSUMED_AT);
    assertThat(docMissing.getTimestamp()).isEqualTo(CONSUMED_AT);
  }

  @Test
  @DisplayName("Should tolerate null fields in payload")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldTolerateNullFields() {
    Map<Object, Object> sparse = new HashMap<>();
    sparse.put("code", "abc123");

    ClickEventDocument doc = ClickBatchWorker.toDocument(sparse, CONSUMED_AT);

    assertThat(doc.getShortCode()).isEqualTo("abc123");
    assertThat(doc.getUserAgent()).isNull();
    assertThat(doc.getIp()).isNull();
  }

  @Test
  @DisplayName("Enrichment derives device from the User-Agent and country via GeoIP; best-effort")
  @TracesRequirement("REQ-ANALYTICS-002")
  void enrichDerivesDeviceAndCountry() {
    GeoIpCountryResolver geo = mock(GeoIpCountryResolver.class);
    when(geo.countryForIp("198.51.100.9")).thenReturn("DE");
    ClickEventDocument doc =
        new ClickEventDocument(
            "abc123",
            CONSUMED_AT,
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) Mobile Safari/537.36",
            "198.51.100.9",
            null,
            null,
            null);

    ClickBatchWorker.enrich(doc, geo);

    assertThat(doc.getDevice()).isEqualTo("mobile");
    assertThat(doc.getCountry()).isEqualTo("DE");
    verify(geo).countryForIp("198.51.100.9");
  }

  @Test
  @DisplayName("Enrichment keeps captured values and never throws for failing geo lookups")
  @TracesRequirement("REQ-ANALYTICS-002")
  void enrichKeepsValuesAndIsSafe() {
    GeoIpCountryResolver geo = mock(GeoIpCountryResolver.class);
    ClickEventDocument doc =
        new ClickEventDocument(
            "abc123", CONSUMED_AT, "SomeBot/1.0", null, "https://ref.example.com", "tablet", "BR");

    ClickBatchWorker.enrich(doc, geo);

    assertThat(doc.getDevice()).isEqualTo("tablet");
    assertThat(doc.getCountry()).isEqualTo("BR");
    assertThat(doc.getReferrer()).isEqualTo("https://ref.example.com");
  }
}
