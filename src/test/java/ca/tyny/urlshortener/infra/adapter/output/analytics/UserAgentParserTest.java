package ca.tyny.urlshortener.infra.adapter.output.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserAgentParserTest {

  @Test
  @DisplayName("Classifies common mobile User-Agents as mobile")
  @TracesRequirement("REQ-ANALYTICS-002")
  void classifiesMobile() {
    assertThat(
            UserAgentParser.device(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
        .isEqualTo("mobile");
    assertThat(
            UserAgentParser.device(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"))
        .isEqualTo("mobile");
    assertThat(UserAgentParser.device("Mozilla/5.0 (Windows Phone 10.0; Android 6.0.1)"))
        .isEqualTo("mobile");
  }

  @Test
  @DisplayName("Classifies tablets separately")
  @TracesRequirement("REQ-ANALYTICS-002")
  void classifiesTablet() {
    assertThat(
            UserAgentParser.device(
                "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 Mobile Safari/604.1"))
        .isEqualTo("tablet");
    assertThat(
            UserAgentParser.device(
                "Mozilla/5.0 (Linux; Android 14; SM-X100) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Tablet"))
        .isEqualTo("tablet");
  }

  @Test
  @DisplayName("Classifies crawlers as bot")
  @TracesRequirement("REQ-ANALYTICS-002")
  void classifiesBot() {
    assertThat(UserAgentParser.device("Googlebot/2.1 (+http://www.google.com/bot.html)"))
        .isEqualTo("bot");
    assertThat(
            UserAgentParser.device(
                "Mozilla/5.0 (compatible; SemrushBot/7.0; +http://www.semrush.com/bot.html)"))
        .isEqualTo("bot");
  }

  @Test
  @DisplayName("Falls back to desktop and tolerates blanks")
  @TracesRequirement("REQ-ANALYTICS-002")
  void classifiesDesktopAndBlanks() {
    assertThat(
            UserAgentParser.device(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"))
        .isEqualTo("desktop");
    assertThat(UserAgentParser.device("")).isNull();
    assertThat(UserAgentParser.device(null)).isNull();
  }
}
