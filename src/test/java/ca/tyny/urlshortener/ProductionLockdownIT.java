package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Production actuator lockdown integration test (Epic 3, story 3.3).
 *
 * <p>Verifies the tiered actuator exposure (resolved debt item 9): liveness/readiness/info are
 * public, health detail never leaks to anonymous or non-ADMIN callers, and every other actuator
 * endpoint requires ADMIN. Runs against the shipped default configuration on a random port with
 * real MongoDB + Redis (Testcontainers), so {@code /actuator/health/readiness} must report UP with
 * Mongo and Redis connected.
 */
@DisplayName("Production Actuator Lockdown Integration Tests")
class ProductionLockdownIT extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @Test
  @DisplayName("liveness probe is public and reports UP")
  void livenessIsPublic() {
    given()
        .when()
        .get("/actuator/health/liveness")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"));
  }

  @Test
  @DisplayName("readiness probe is public and reports UP with MongoDB and Redis up")
  void readinessIsPublicAndUpWhenBackendsUp() {
    String body =
        given()
            .when()
            .get("/actuator/health/readiness")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    assertThat(body).contains("\"status\":\"UP\"");
    // when-authorized + anonymous caller: no indicator components/details leak.
    assertThat(body).doesNotContain("components", "\"redis\"", "\"mongo\"", "\"db\"");
  }

  @Test
  @DisplayName("full health endpoint leaks no detail to an anonymous caller (401)")
  void fullHealthIsLockedForAnonymous() {
    given().when().get("/actuator/health").then().statusCode(401);
    given().when().get("/actuator/health?show=components").then().statusCode(401);
    given().when().get("/actuator/health?show=details").then().statusCode(401);
  }

  @Test
  @DisplayName("metrics and prometheus require authorization")
  void metricsRequireAuthorization() {
    given().when().get("/actuator/prometheus").then().statusCode(401);
    given().when().get("/actuator/metrics").then().statusCode(401);
    given().when().get("/actuator/metrics/jvm.memory.used").then().statusCode(401);
  }

  @Test
  @DisplayName("other non-public actuator endpoints require ADMIN")
  void nonPublicActuatorEndpointsRequireAdmin() {
    given().when().get("/actuator").then().statusCode(401);
    given().when().get("/actuator/beans").then().statusCode(401);
    given().when().get("/actuator/env").then().statusCode(401);
    given().when().get("/actuator/loggers").then().statusCode(401);
    given().when().get("/actuator/conditions").then().statusCode(401);
  }

  @Test
  @DisplayName("info is public")
  void infoIsPublic() {
    given().when().get("/actuator/info").then().statusCode(200);
  }

  @Test
  @DisplayName("authenticated non-ADMIN user still cannot read health detail or metrics")
  void authenticatedNonAdminCannotReadHealthOrMetrics() {
    // Register + login a plain ROLE_USER account.
    String email = "locked@test.com";
    String password = "password123";
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"Locked User\",\"email\":\""
                + email
                + "\",\"password\":\""
                + password
                + "\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);

    String userToken =
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .path("token");

    // The User model carries no roles and CustomUserDetailsService grants empty authorities, so
    // ROLE_ADMIN is currently unattainable: every authenticated caller is still locked out
    // (authenticated-but-forbidden yields 403; anonymous yields 401).
    given()
        .header("Authorization", "Bearer " + userToken)
        .when()
        .get("/actuator")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", "Bearer " + userToken)
        .when()
        .get("/actuator/health")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", "Bearer " + userToken)
        .when()
        .get("/actuator/metrics")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", "Bearer " + userToken)
        .when()
        .get("/actuator/prometheus")
        .then()
        .statusCode(403);
  }
}
