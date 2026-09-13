package ca.tyny.urlshortener.infra.adapter.output.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.model.ClickEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisClickEventQueueTest {

  @Test
  @DisplayName("Should map ClickEvent to stream payload with UTC instant")
  @TracesRequirement("REQ-ANALYTICS-006")
  void shouldMapToPayload() {
    LocalDateTime local = LocalDateTime.of(2026, 8, 26, 12, 0);
    ClickEvent event =
        new ClickEvent(
            "abc123",
            local,
            "UA/1.0",
            "203.0.113.7",
            "https://ref.example.com/page",
            "desktop",
            "BR");

    Map<String, String> payload = RedisClickEventQueue.toPayload(event);

    assertThat(payload)
        .containsEntry(RedisClickEventQueue.FIELD_CODE, "abc123")
        .containsEntry(RedisClickEventQueue.FIELD_TIMESTAMP, "2026-08-26T12:00:00Z")
        .containsEntry(RedisClickEventQueue.FIELD_USER_AGENT, "UA/1.0")
        .containsEntry(RedisClickEventQueue.FIELD_IP, "203.0.113.7")
        .containsEntry(RedisClickEventQueue.FIELD_REFERRER, "https://ref.example.com/page")
        .containsEntry(RedisClickEventQueue.FIELD_DEVICE, "desktop")
        .containsEntry(RedisClickEventQueue.FIELD_COUNTRY, "BR");
  }

  @Test
  @DisplayName("Should default null timestamp to a parseable instant")
  @TracesRequirement("REQ-ANALYTICS-002")
  void shouldDefaultNullTimestamp() {
    ClickEvent event = new ClickEvent("abc123", null, null, null);

    Map<String, String> payload = RedisClickEventQueue.toPayload(event);

    assertThat(Instant.parse(payload.get(RedisClickEventQueue.FIELD_TIMESTAMP))).isNotNull();
    assertThat(payload.get(RedisClickEventQueue.FIELD_CODE)).isEqualTo("abc123");
    assertThat(payload).doesNotContainKey(RedisClickEventQueue.FIELD_REFERRER);
  }
}
