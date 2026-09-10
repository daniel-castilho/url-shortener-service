package ca.tyny.urlshortener.core.model;

import java.time.LocalDateTime;

public record ClickEvent(
    String shortCode,
    LocalDateTime timestamp,
    String userAgent,
    String ip,
    String referrer,
    String device,
    String country) {

  public ClickEvent(String shortCode, LocalDateTime timestamp, String userAgent, String ip) {
    this(shortCode, timestamp, userAgent, ip, null, null, null);
  }
}
