package ca.tyny.urlshortener.infra.archunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Epic 2 story 2.1 — the sink sanitizer must neutralize every CR/LF a client-controlled value can
 * carry (CWE-117 log forging, lessons.md §Security). Each class that logs client input owns a local
 * package-private {@code logSafe} twin; the test reaches it reflectively so the helper stays a
 * non-API implementation detail.
 */
class LogSafeSinkTest {

  private static final List<String> SINK_CLASSES =
      List.of(
          "ca.tyny.urlshortener.infra.adapter.input.rest.advice.GlobalExceptionHandler",
          "ca.tyny.urlshortener.infra.adapter.output.analytics.RedisClickEventQueue",
          "ca.tyny.urlshortener.infra.adapter.output.persistence.MongoCustomDomainRepository",
          "ca.tyny.urlshortener.infra.adapter.output.redis.RedisCustomDomainRegistry",
          "ca.tyny.urlshortener.infra.adapter.output.validation.DefaultUrlValidator");

  private static String sanitize(String className, String value) throws Exception {
    Method logSafe = Class.forName(className).getDeclaredMethod("logSafe", String.class);
    logSafe.setAccessible(true);
    return (String) logSafe.invoke(null, value);
  }

  static Stream<Arguments> sinks() {
    return SINK_CLASSES.stream().map(Arguments::of);
  }

  @ParameterizedTest(name = "log forging payload neutralized at {0}")
  @MethodSource("sinks")
  @DisplayName("logSafe strips CR and LF from client-controlled values (CWE-117)")
  void logSafeStripsCrLf(String className) throws Exception {
    assertEquals("id_a_b__ 2026 forged", sanitize(className, "id\na\rb\r\n 2026 forged"));
  }

  @ParameterizedTest(name = "clean values pass through unchanged at {0}")
  @MethodSource("sinks")
  void logSafePassthrough(String className) throws Exception {
    assertEquals("short-abc123", sanitize(className, "short-abc123"));
    assertNull(sanitize(className, null));
  }
}
