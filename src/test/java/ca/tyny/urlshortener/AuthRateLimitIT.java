package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves auth rate limiting (AUTH scope) against the real Redis token bucket — same pattern as
 * {@link RedirectRateLimitIT}. With {@code auth-limit=2}, the first two calls to {@code POST
 * /api/v1/auth/login} succeed and the third is 429. Scope isolation is verified (exhausting AUTH
 * never blocks SHORTEN/REDIRECT). Register is intentionally not rate-limited.
 *
 * <p>Each test registers a unique user so no login collisions occur across tests.
 */
@DisplayName("Auth Rate Limit Integration Tests")
@TestPropertySource(properties = {"rate-limiter.auth-limit=2", "rate-limiter.auth-window=PT1M"})
class AuthRateLimitIT extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  private String registerUser(String email) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"RL User\",\"email\":\"" + email + "\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200)
        .extract()
        .path("refreshToken");
  }

  @Test
  @DisplayName("Login is rate limited: first 2 calls succeed, third is 429 + Retry-After")
  @TracesRequirement("REQ-AUTH-010")
  void loginThrottledAfterCapacity() {
    String email = "rl-login-" + UUID.randomUUID() + "@test.com";
    registerUser(email);
    String body = "{\"email\":\"" + email + "\",\"password\":\"password123\"}";

    // Allowed: 2 requests (capacity = 2)
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200);

    // Blocked: 3rd request exceeds capacity
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/auth/login")
        .then()
        .statusCode(429)
        .header("Retry-After", notNullValue())
        .header("RateLimit-Limit", equalTo("*"))
        .header("RateLimit-Remaining", equalTo("0"));
  }

  @Test
  @DisplayName("Refresh is also rate limited (AUTH scope): 2 refreshes succeed, 3rd is 429")
  @TracesRequirement("REQ-AUTH-010")
  void refreshThrottledAfterCapacity() {
    String email = "rl-refresh-" + UUID.randomUUID() + "@test.com";
    String loginBody = "{\"email\":\"" + email + "\",\"password\":\"password123\"}";
    // Register + login consumes 1 AUTH token; the other bucket token is still available.
    registerUser(email);
    String refresh =
        given()
            .contentType(ContentType.JSON)
            .body(loginBody)
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .path("refreshToken");

    given()
        .cookie("refresh_token", refresh)
        .contentType(ContentType.JSON)
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(200);
    // Bucket is now empty (2 tokens consumed: login + refresh).
    given()
        .cookie("refresh_token", refresh)
        .contentType(ContentType.JSON)
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(429)
        .header("Retry-After", notNullValue());
  }

  @Test
  @DisplayName("Register is NOT rate limited: 3 registrations all succeed")
  @TracesRequirement("REQ-AUTH-001")
  void registerNotRateLimited() {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"User A\",\"email\":\"rl-reg-a-"
                + UUID.randomUUID()
                + "@test.com\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"User B\",\"email\":\"rl-reg-b-"
                + UUID.randomUUID()
                + "@test.com\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"User C\",\"email\":\"rl-reg-c-"
                + UUID.randomUUID()
                + "@test.com\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);
  }

  @Test
  @DisplayName("Auth scope isolation: exhausting AUTH never blocks SHORTEN")
  @TracesRequirement("REQ-RATE-006")
  void authScopeDoesNotExhaustShorten() {
    String email = "rl-iso-" + UUID.randomUUID() + "@test.com";
    registerUser(email);
    String body = "{\"email\":\"" + email + "\",\"password\":\"password123\"}";

    // Exhaust the AUTH bucket (2 calls)
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200);

    // SHORTEN bucket is independent — 200
    given()
        .contentType(ContentType.JSON)
        .body("{\"originalUrl\":\"https://scope-isolation.example.com\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(200)
        .body("shortUrl", notNullValue());
  }
}
