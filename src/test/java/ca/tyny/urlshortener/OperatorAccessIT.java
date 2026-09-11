package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Operator role over the actuator tiers (debt 26 — resolved).
 *
 * <p>Boots the app with operator credentials set ({@code security.operator.*}) and verifies the
 * least-privilege contract over HTTP Basic: health, metrics, prometheus and circuit breakers are
 * reachable; env/beans/index are NOT; wrong credentials fail closed; JWT-user tokens still get 403
 * on the same tiers. Runs against real MongoDB + Redis (Testcontainers).
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.security.operator.username=ops-it",
      "app.security.operator.password=it-operator-pw-16chars",
    })
@DisplayName("Operator role actuator access (debt 26)")
class OperatorAccessIT extends BaseIntegrationTest {

  private static final String OPERATOR = "ops-it";
  private static final String PASSWORD = "it-operator-pw-16chars";

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  @Test
  @DisplayName("operator reads /actuator/health with details (200)")
  void operatorReadsHealth() {
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
    // when-authorized: an authenticated caller now sees the component details
    assertThat(body).contains("\"status\":\"UP\"");
  }

  @Test
  @DisplayName("operator reads /actuator/metrics and a named metric (200)")
  void operatorReadsMetrics() {
    given()
        .auth()
        .preemptive()
        .basic(OPERATOR, PASSWORD)
        .when()
        .get("/actuator/metrics")
        .then()
        .statusCode(200);
    given()
        .auth()
        .preemptive()
        .basic(OPERATOR, PASSWORD)
        .when()
        .get("/actuator/metrics/jvm.memory.used")
        .then()
        .statusCode(200);
  }

  @Test
  @DisplayName("operator reads /actuator/prometheus (200, scrape body)")
  void operatorReadsPrometheus() {
    String body =
        given()
            .auth()
            .preemptive()
            .basic(OPERATOR, PASSWORD)
            .when()
            .get("/actuator/prometheus")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertThat(body).contains("jvm_memory_used_bytes");
  }

  @Test
  @DisplayName("operator reads /actuator/circuitbreakers (200, databaseCb present)")
  void operatorReadsCircuitBreakers() {
    String body =
        given()
            .auth()
            .preemptive()
            .basic(OPERATOR, PASSWORD)
            .when()
            .get("/actuator/circuitbreakers")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertThat(body).contains("databaseCb");
  }

  @Test
  @DisplayName("operator is least-privilege: env/beans/index are forbidden (403)")
  void operatorIsLeastPrivilege() {
    given()
        .auth()
        .preemptive()
        .basic(OPERATOR, PASSWORD)
        .when()
        .get("/actuator/env")
        .then()
        .statusCode(403);
    given()
        .auth()
        .preemptive()
        .basic(OPERATOR, PASSWORD)
        .when()
        .get("/actuator/beans")
        .then()
        .statusCode(403);
    given()
        .auth()
        .preemptive()
        .basic(OPERATOR, PASSWORD)
        .when()
        .get("/actuator")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("wrong operator password fails closed (401)")
  void wrongPasswordFailsClosed() {
    given()
        .auth()
        .preemptive()
        .basic(OPERATOR, "wrong-password")
        .when()
        .get("/actuator/health")
        .then()
        .statusCode(401);
  }

  @Test
  @DisplayName("unknown user with Basic credentials fails closed (401)")
  void unknownBasicUserFailsClosed() {
    given()
        .auth()
        .preemptive()
        .basic("nosuchuser", "whatever")
        .when()
        .get("/actuator/metrics")
        .then()
        .statusCode(401);
  }

  @Test
  @DisplayName("anonymous still gets 401 on tiered endpoints (no regression)")
  void anonymousStillLocked() {
    given().when().get("/actuator/health").then().statusCode(401);
    given().when().get("/actuator/metrics").then().statusCode(401);
    given().when().get("/actuator/prometheus").then().statusCode(401);
  }

  @Test
  @DisplayName("plain JWT user (no roles) still gets 403 on the tiers")
  void jwtUserStillForbidden() {
    String email = "plainuser@test.com";
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"Plain\",\"email\":\"" + email + "\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);
    String token =
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"" + email + "\",\"password\":\"password123\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .path("token");

    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/actuator/health")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/actuator/metrics")
        .then()
        .statusCode(403);
  }
}
