package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Custom health-indicator wiring proof (audit follow-up to PR 13).
 *
 * <p>{@code UrlShortenerHealthIndicator} had zero test references when it shipped. Operator/
 * lockdown suites assert the actuator tiers but never the custom details, so the indicator could
 * silently vanish from the health endpoint. This test boots the real app (Testcontainers Mongo +
 * Redis) with operator credentials and asserts the full-health JSON carries the custom Mongo and
 * Redis component checks — proving the indicator is registered with the actuator, not just
 * unit-testable.
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.security.operator.username=ops-it",
      "app.security.operator.password=it-operator-pw-16chars",
    })
@DisplayName("Custom health indicator is wired into /actuator/health")
class HealthIndicatorDetailsIT extends BaseIntegrationTest {

  private static final String OPERATOR = "ops-it";
  private static final String PASSWORD = "it-operator-pw-16chars";

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @Test
  @DisplayName("full health JSON carries the custom mongo and redis component details")
  void fullHealthCarriesCustomComponents() {
    String body =
        given()
            .auth()
            .preemptive()
            .basic(OPERATOR, PASSWORD)
            .when()
            .get("/actuator/health")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    // components.urlShortener is the custom indicator's contributor name; its details carry
    // the mongo/redis checks (see UrlShortenerHealthIndicator), proving it is registered with
    // the actuator — not just unit-testable.
    assertThat(body).contains("urlShortener");
    assertThat(body).contains("\"components\":{").contains("\"mongo\":\"UP\"");
    assertThat(body).contains("\"redis\":\"UP\"");
    assertThat(body).contains("\"status\":\"UP\"");
  }
}
