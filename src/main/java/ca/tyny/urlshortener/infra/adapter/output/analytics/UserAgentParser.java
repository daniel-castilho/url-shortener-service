package ca.tyny.urlshortener.infra.adapter.output.analytics;

import java.util.Locale;

/**
 * Minimal, dependency-free User-Agent device classification.
 *
 * <p>Coarse bucketing is intentional: the values feed analytics breakdowns, not UA sniffing. Covers
 * common mobile/tablet markers, crawlers and a desktop fallback.
 */
public final class UserAgentParser {

  private UserAgentParser() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Classifies a raw User-Agent into the coarse device bucket {@code desktop | mobile | tablet |
   * bot}, or {@code null} when absent.
   */
  public static String device(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return null;
    }
    String lower = userAgent.toLowerCase(Locale.ROOT);

    if (lower.contains("bot")
        || lower.contains("spider")
        || lower.contains("crawler")
        || lower.contains("feedfetcher")) {
      return "bot";
    }
    if (lower.contains("ipad")
        || lower.contains("tablet")
        || lower.contains("kindle")
        || lower.contains("x11;")) {
      return "tablet";
    }
    if (lower.contains("mobi")
        || lower.contains("iphone")
        || lower.contains("android")
        || lower.contains("windows phone")) {
      return "mobile";
    }
    return "desktop";
  }
}
