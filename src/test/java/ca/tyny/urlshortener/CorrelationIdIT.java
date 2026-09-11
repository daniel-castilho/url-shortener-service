package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import io.restassured.RestAssured;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Correlation-Id integration tests (Epic 3, story 3.1).
 *
 * <p>Verifies the {@code RequestCorrelationFilter} behaviour end-to-end against a running app: a
 * safe inbound {@code X-Request-Id} is echoed, an absent or unsafe one is replaced by a UUID, and
 * every log line written on the request thread carries the resolved id in the MDC (log pattern
 * {@code %X{request_id}}). Requests hit {@code /nonexistent-code} so {@code GlobalExceptionHandler}
 * definitely logs a "URL not found" warning on the request thread for the id assertion.
 */
@DisplayName("Correlation-Id Integration Tests")
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdIT extends BaseIntegrationTest {

  private static final Pattern UUID_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @Test
  @DisplayName("Safe inbound X-Request-Id is echoed and present in the request-thread log line")
  void echoesValidClientProvidedRequestId(CapturedOutput output) {
    String requestId = "corr-abc-123.";

    String responseId =
        given()
            .header("X-Request-Id", requestId)
            .when()
            .get("/nonexistent-code")
            .then()
            .statusCode(404)
            .extract()
            .header("X-Request-Id");

    assertThat(responseId).isEqualTo(requestId);
    assertThat(waitForLine(output, requestId)).isTrue();
  }

  @Test
  @DisplayName("Absent X-Request-Id generates a UUID echoed on the response and in the logs")
  void generatesUuidWhenAbsent(CapturedOutput output) {
    String responseId =
        given()
            .when()
            .get("/nonexistent-code")
            .then()
            .statusCode(404)
            .extract()
            .header("X-Request-Id");

    assertThat(UUID_PATTERN.matcher(responseId).matches())
        .as("response X-Request-Id is a UUID")
        .isTrue();
    assertThat(waitForLine(output, responseId)).isTrue();
  }

  @Test
  @DisplayName("Oversized (unsafe) X-Request-Id is replaced by a generated UUID")
  void rejectsOversizedHeader(CapturedOutput output) {
    String oversized = "a".repeat(80);

    String responseId =
        given()
            .header("X-Request-Id", oversized)
            .when()
            .get("/nonexistent-code")
            .then()
            .statusCode(404)
            .extract()
            .header("X-Request-Id");

    assertThat(responseId).isNotEqualTo(oversized);
    assertThat(UUID_PATTERN.matcher(responseId).matches())
        .as("replaced X-Request-Id is a UUID")
        .isTrue();
    assertThat(waitForLine(output, responseId)).isTrue();
  }

  /**
   * Polls the captured stdout until a log line that carries {@code requestId} in the MDC slot next
   * to a "URL not found" message appears (the request-thread warn from GlobalExceptionHandler). The
   * console appender is async, so allow a small window for the write.
   */
  private boolean waitForLine(CapturedOutput output, String requestId) {
    for (int i = 0; i < 100; i++) {
      String logs = output.getOut();
      for (String line : logs.split("\\R")) {
        if (line.contains(requestId + " - URL not found:")) {
          return true;
        }
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }
}
