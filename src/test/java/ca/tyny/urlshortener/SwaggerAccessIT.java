package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Swagger access integration test (Epic 14 — Fase C).
 *
 * <p>Verifies that the exact `/v3/api-docs` path (and the Swagger UI) are reachable with the
 * default dev/staging profile, where `app.security.swagger.enabled` defaults to `true`. The Ant
 * matcher fix is under test here: previously `/v3/api-docs` (no trailing slash) matched only the
 * authenticated fallback (401) and `/v3/api-docs/` returned 500 — both must now be 200.
 */
@DisplayName("Swagger Access Integration Tests")
class SwaggerAccessIT extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @Test
  @DisplayName("exact /v3/api-docs returns the OpenAPI spec anonymously")
  void exactApiDocsIsPublic() {
    given().when().get("/v3/api-docs").then().statusCode(200).body("openapi", notNullValue());
  }

  @Test
  @DisplayName("context /v3/api-docs/swagger-config returns the UI configuration")
  void swaggerConfigIsPublic() {
    given().when().get("/v3/api-docs/swagger-config").then().statusCode(200);
  }

  @Test
  @DisplayName("Swagger UI is reachable anonymously")
  void swaggerUiIsPublic() {
    given().when().get("/swagger-ui/index.html").then().statusCode(200);
    given().when().get("/swagger-ui.html").then().statusCode(200);
  }
}
