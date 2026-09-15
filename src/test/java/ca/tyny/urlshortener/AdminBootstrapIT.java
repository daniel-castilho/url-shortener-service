package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.infra.security.JwtTokenProvider;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Admin role bootstrap from {@code app.admin-emails} and legacy-token compatibility (ADR 0011).
 *
 * <p>Boots against real MongoDB + Redis (Testcontainers). Verifies: admin email → role ADMIN in /me
 * and login; normal email → role USER; a legacy access token with no role claim is authenticated as
 * ROLE_USER and /me returns USER (the claim is absent, legacy behavior).
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.admin-emails=admin@example.com",
      "app.security.operator.username=ops-admin-it",
      "app.security.operator.password=it-operator-pw-16chars",
    })
@DisplayName("Admin role bootstrap + legacy token integration (ADR 0011)")
class AdminBootstrapIT extends BaseIntegrationTest {

  private static final String ACCESS_COOKIE = "access_token";

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @Autowired private JwtTokenProvider jwtTokenProvider;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString())).thenReturn(RateLimitVerdict.allow(100));
    RestAssured.port = port;
  }

  @Test
  @TracesRequirement("REQ-AUTH-012")
  @DisplayName("admin email resolves to ADMIN role on login and /me")
  void adminEmailResolvesToAdminRole() {
    String email = "admin@example.com";
    register(email);

    String token = login(email, "password123").then().statusCode(200).extract().path("token");

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200)
        .body("role", equalTo("ADMIN"))
        .body("email", equalTo(email));
  }

  @Test
  @TracesRequirement("REQ-AUTH-012")
  @DisplayName("normal user resolves to USER role on login and /me")
  void normalUserResolvesToUserRole() {
    String email = "user@example.com";
    register(email);

    String token = login(email, "password123").then().statusCode(200).extract().path("token");

    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200)
        .body("role", equalTo("USER"))
        .body("email", equalTo(email));
  }

  @Test
  @TracesRequirement("REQ-AUTH-011")
  @DisplayName("legacy token without role claim is authenticated as ROLE_USER and /me returns USER")
  void legacyTokenWithoutClaimIsAuthenticatedAsUserRole() {
    String email = "legacy@example.com";
    register(email);

    String legacyToken = jwtTokenProvider.generateToken(email, null);

    given()
        .header("Authorization", "Bearer " + legacyToken)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200)
        .body("role", equalTo("USER"))
        .body("email", equalTo(email));
  }

  private String register(String email) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"Bootstrap User\",\"email\":\""
                + email
                + "\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200)
        .extract()
        .path("token");
  }

  private io.restassured.response.Response login(String email, String password) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
        .post("/api/v1/auth/login");
  }
}
