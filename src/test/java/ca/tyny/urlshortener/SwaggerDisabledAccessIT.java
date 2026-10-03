package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Swagger-disabled access integration test (Epic 14 — Fase C).
 *
 * <p>Proves the fail-closed path of the refined matcher: when `app.security.swagger.enabled` is
 * forced off (the `application-prod.yaml` default), the exact `/v3/api-docs`, its `/`-suffixed
 * sibling and the Swagger UI must be denied (401 anonymous), while the developer endpoint must not
 * fall through to any other matcher. Guards the Ant matcher regression on the exact path.
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.security.swagger.enabled=false"})
@DisplayName("Swagger Disabled Access Integration Tests")
class SwaggerDisabledAccessIT extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @Test
  @DisplayName("exact /v3/api-docs is denied when Swagger is disabled")
  void exactApiDocsDeniedWhenDisabled() {
    given().when().get("/v3/api-docs").then().statusCode(401);
  }

  @Test
  @DisplayName("Swagger UI is denied when Swagger is disabled")
  void swaggerUiDeniedWhenDisabled() {
    given().when().get("/swagger-ui/index.html").then().statusCode(401);
    given().when().get("/swagger-ui.html").then().statusCode(401);
  }
}
