package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Security headers integration test.
 *
 * <p>Verifies that the security headers declared via Spring Security's {@code .headers()} in {@code
 * SecurityConfig} are present, with single (non-overwritten) values, on representative routes:
 * actuator liveness (public), the anonymous shorten API, and the redirect path.
 *
 * <p>Required headers (Epic 2, story 2.4): {@code X-Content-Type-Options: nosniff}, {@code
 * X-Frame-Options: DENY}, {@code Referrer-Policy: strict-origin-when-cross-origin}.
 */
@DisplayName("Security Headers Integration Tests")
class SecurityHeadersIT extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @Test
  @DisplayName("Security headers present on the actuator liveness route")
  void headersOnActuatorLiveness() {
    given()
        .when()
        .get("/actuator/health/liveness")
        .then()
        .statusCode(200)
        .header("X-Content-Type-Options", equalTo("nosniff"))
        .header("X-Frame-Options", equalTo("DENY"))
        .header("Referrer-Policy", equalTo("strict-origin-when-cross-origin"));
  }

  @Test
  @DisplayName("Security headers present on the anonymous shorten API route")
  void headersOnShortenApi() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"url\":\"not-a-url\"}")
        .when()
        .post("/api/v1/urls")
        .then()
        .statusCode(org.hamcrest.Matchers.anyOf(equalTo(400), equalTo(422)))
        .header("X-Content-Type-Options", equalTo("nosniff"))
        .header("X-Frame-Options", equalTo("DENY"))
        .header("Referrer-Policy", equalTo("strict-origin-when-cross-origin"));
  }

  @Test
  @DisplayName("Security headers present on the redirect path")
  void headersOnRedirectPath() {
    given()
        .when()
        .get("/nonexistent-code")
        .then()
        .statusCode(404)
        .header("X-Content-Type-Options", equalTo("nosniff"))
        .header("X-Frame-Options", equalTo("DENY"))
        .header("Referrer-Policy", equalTo("strict-origin-when-cross-origin"));
  }
}
