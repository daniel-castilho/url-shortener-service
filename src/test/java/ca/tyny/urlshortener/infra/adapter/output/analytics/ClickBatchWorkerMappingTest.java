package ca.tyny.urlshortener.infra.adapter.output.analytics;

import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickEventDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClickBatchWorkerMappingTest {

    private static final Instant CONSUMED_AT = Instant.parse("2026-08-26T13:00:00Z");

    @Test
    @DisplayName("Should map stream payload to document with parsed instant")
    void shouldMapPayloadToDocument() {
        Map<Object, Object> value = new HashMap<>();
        value.put("code", "abc123");
        value.put("ts", "2026-08-26T12:00:00Z");
        value.put("ua", "UA/2.0");
        value.put("ip", "198.51.100.9");

        ClickEventDocument doc = ClickBatchWorker.toDocument(value, CONSUMED_AT);

        assertThat(doc.getShortCode()).isEqualTo("abc123");
        assertThat(doc.getTimestamp()).isEqualTo(Instant.parse("2026-08-26T12:00:00Z"));
        assertThat(doc.getUserAgent()).isEqualTo("UA/2.0");
        assertThat(doc.getIp()).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("Should fall back to consumedAt on missing or malformed timestamp")
    void shouldFallbackTimestamp() {
        Map<Object, Object> malformed = new HashMap<>();
        malformed.put("code", "abc123");
        malformed.put("ts", "not-a-timestamp");

        ClickEventDocument docMalformed = ClickBatchWorker.toDocument(malformed, CONSUMED_AT);
        ClickEventDocument docMissing =
                ClickBatchWorker.toDocument(Map.of("code", "x"), CONSUMED_AT);

        assertThat(docMalformed.getTimestamp()).isEqualTo(CONSUMED_AT);
        assertThat(docMissing.getTimestamp()).isEqualTo(CONSUMED_AT);
    }

    @Test
    @DisplayName("Should tolerate null fields in payload")
    void shouldTolerateNullFields() {
        Map<Object, Object> sparse = new HashMap<>();
        sparse.put("code", "abc123");

        ClickEventDocument doc = ClickBatchWorker.toDocument(sparse, CONSUMED_AT);

        assertThat(doc.getShortCode()).isEqualTo("abc123");
        assertThat(doc.getUserAgent()).isNull();
        assertThat(doc.getIp()).isNull();
    }
}
