package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end cookie auth flow (ADR 0010).
 *
 * <p>Boots the app against real MongoDB + Redis (Testcontainers) and verifies the additive
 * HttpOnly-cookie transport over the same-origin SPA contract: login/register/refresh set the
 * cookie pair, the JWT filter authenticates from the {@code access_token} cookie (Bearer wins when
 * both present), {@code GET /api/v1/auth/me} returns the identity (401 anonymous), {@code POST
 * /api/v1/auth/logout} clears both cookies idempotently, and the refresh cookie carries the
 * minimized {@code Path=/api/v1/auth/refresh}. Regression guards: Bearer-only still works, public
 * redirect untouched, JSON dual-write intact, operator BasicAuth coexists.
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Operator BasicAuth (debt 26) — regression for filter coexistence.
      "app.security.operator.username=ops-it",
      "app.security.operator.password=it-operator-pw-16chars",
    })
@DisplayName("Cookie-based auth end-to-end (ADR 0010)")
class AuthCookieIT extends BaseIntegrationTest {

  private static final String ACCESS_COOKIE = "access_token";
  private static final String REFRESH_COOKIE = "refresh_token";
  private static final String REFRESH_COOKIE_PATH = "/api/v1/auth/refresh";
  private static final String OPERATOR = "ops-it";
  private static final String OPERATOR_PASSWORD = "it-operator-pw-16chars";

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString())).thenReturn(RateLimitVerdict.allow(100));
    RestAssured.port = port;
    RestAssured.basePath = "/";
  }

  private Response login(String email, String password) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
        .post("/api/v1/auth/login");
  }

  private String register(String email) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"Cookie User\",\"email\":\"" + email + "\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200)
        .extract()
        .path("token");
  }

  private String shorten(String url) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"originalUrl\":\"" + url + "\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(200)
        .extract()
        .path("id");
  }

  @Test
  @TracesRequirement("REQ-AUTH-002")
  @DisplayName("login sets access_token/refresh_token cookies with exact attributes")
  void loginSetsCookies() {
    // Given
    String email = "cookie1@test.com";
    register(email);

    // When
    Response response = login(email, "password123");

    // Then - attributes asserted by value, not just presence
    Cookie access = response.getDetailedCookie(ACCESS_COOKIE);
    Cookie refresh = response.getDetailedCookie(REFRESH_COOKIE);

    assertThat(access.getValue()).isNotEmpty();
    assertThat(access.isHttpOnly()).isTrue();
    assertThat(access.isSecured()).isTrue();
    assertThat(access.getSameSite()).isEqualTo("Lax");
    assertThat(access.getPath()).isEqualTo("/");
    // app.jwt.expiration-ms = 86400000 -> 86400 s
    assertThat(access.getMaxAge()).isEqualTo(86400);

    assertThat(refresh.getValue()).isNotEmpty();
    assertThat(refresh.isHttpOnly()).isTrue();
    assertThat(refresh.isSecured()).isTrue();
    assertThat(refresh.getSameSite()).isEqualTo("Lax");
    assertThat(refresh.getPath()).isEqualTo(REFRESH_COOKIE_PATH);
    // app.jwt.refresh-expiration-ms = 604800000 -> 604800 s
    assertThat(refresh.getMaxAge()).isEqualTo(604800);

    // Dual-write (D1): the JSON body still carries the full set, byte-identical to today.
    assertThat(response.asString()).contains("\"token\"", "\"refreshToken\"");
  }

  @Test
  @TracesRequirement("REQ-AUTH-005")
  @DisplayName("request with only the access_token cookie authenticates on /me")
  void cookieOnlyAuthenticatesMe() {
    // Given
    String email = "cookie2@test.com";
    register(email);
    Response login = login(email, "password123");
    String access = login.getCookie(ACCESS_COOKIE);

    // When/Then - no Authorization header at all
    given()
        .cookie(ACCESS_COOKIE, access)
        .when()
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200)
        .body("email", org.hamcrest.Matchers.equalTo(email))
        .body("userId", org.hamcrest.Matchers.notNullValue())
        .body("name", org.hamcrest.Matchers.equalTo("Cookie User"));
  }

  @Test
  @TracesRequirement("REQ-AUTH-007")
  @DisplayName("request with only Bearer still authenticates on /me (regression)")
  void bearerOnlyAuthenticatesMe() {
    // Given
    String email = "cookie3@test.com";
    String token = register(email);

    // When/Then - no cookie
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200)
        .body("email", org.hamcrest.Matchers.equalTo(email));
  }

  @Test
  @TracesRequirement("REQ-AUTH-007")
  @DisplayName("GET /{id} with no cookies still returns 302 + Location (public redirect untouched)")
  void publicRedirectUntouched() {
    // Given
    String url = "https://public-redirect.example.com/path";
    String id = shorten(url);

    // When/Then - no cookies, no auth
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/" + id)
        .then()
        .statusCode(302)
        .header("Location", url);
  }

  @Test
  @TracesRequirement("REQ-AUTH-009")
  @DisplayName("refresh with cookie only returns 200, rotates access cookie, keeps refresh value")
  void refreshViaCookieOnly() {
    // Given
    String email = "cookie4@test.com";
    register(email);
    Response login = login(email, "password123");
    String refresh = login.getCookie(REFRESH_COOKIE);

    // When - no body at all, only the refresh_token cookie
    Response refreshed =
        given()
            .cookie(REFRESH_COOKIE, refresh)
            .contentType(ContentType.JSON)
            .post("/api/v1/auth/refresh");

    // Then
    refreshed.then().statusCode(200);
    String newAccess = refreshed.getDetailedCookie(ACCESS_COOKIE).getValue();
    assertThat(newAccess).isNotEmpty();
    // D5: refresh cookie value unchanged, only Max-Age slides
    assertThat(refreshed.getDetailedCookie(REFRESH_COOKIE).getValue()).isEqualTo(refresh);
    assertThat(refreshed.getDetailedCookie(REFRESH_COOKIE).getPath())
        .isEqualTo(REFRESH_COOKIE_PATH);
  }

  @Test
  @TracesRequirement("REQ-AUTH-009")
  @DisplayName("refresh with neither body token nor cookie returns 401 (not 400)")
  void refreshWithoutTokenOrCookie() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(401);
  }

  @Test
  @TracesRequirement("REQ-AUTH-008")
  @DisplayName("logout returns 204 and clears both cookies with exact paths and Max-Age 0")
  void logoutClearsBothCookies() {
    // Given
    String email = "cookie5@test.com";
    register(email);
    Response login = login(email, "password123");
    String access = login.getCookie(ACCESS_COOKIE);
    String refresh = login.getCookie(REFRESH_COOKIE);

    // When
    Response logout =
        given()
            .cookie(ACCESS_COOKIE, access)
            .cookie(REFRESH_COOKIE, refresh)
            .post("/api/v1/auth/logout");

    // Then
    logout.then().statusCode(204);
    Cookie clearedAccess = logout.getDetailedCookie(ACCESS_COOKIE);
    Cookie clearedRefresh = logout.getDetailedCookie(REFRESH_COOKIE);
    assertThat(clearedAccess.getMaxAge()).isZero();
    assertThat(clearedAccess.getPath()).isEqualTo("/");
    assertThat(clearedAccess.isHttpOnly()).isTrue();
    assertThat(clearedAccess.isSecured()).isTrue();
    assertThat(clearedRefresh.getMaxAge()).isZero();
    assertThat(clearedRefresh.getPath()).isEqualTo(REFRESH_COOKIE_PATH);
  }

  @Test
  @TracesRequirement("REQ-AUTH-007")
  @DisplayName("/me with a valid session returns exactly {userId, email, name}")
  void meReturnsIdentityTrio() {
    // Given
    String email = "cookie6@test.com";
    register(email);
    Response login = login(email, "password123");
    Cookie access = login.getDetailedCookie(ACCESS_COOKIE);

    // When
    String body =
        given()
            .cookie(ACCESS_COOKIE, access.getValue())
            .when()
            .get("/api/v1/auth/me")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    // Then - exactly the trio, no tokens
    assertThat(body).contains("\"userId\"", "\"email\"", "\"name\"");
    assertThat(body).doesNotContain("\"token\"", "\"refreshToken\"");
  }

  @Test
  @TracesRequirement("REQ-AUTH-009")
  @DisplayName("refresh_token cookie carries the minimized Path=/api/v1/auth/refresh")
  void refreshCookiePathIsMinimized() {
    // Given
    String email = "cookie7@test.com";
    register(email);
    Response login = login(email, "password123");

    // Then
    Cookie access = login.getDetailedCookie(ACCESS_COOKIE);
    Cookie refresh = login.getDetailedCookie(REFRESH_COOKIE);
    assertThat(refresh.getPath()).isEqualTo(REFRESH_COOKIE_PATH);
    assertThat(refresh.isHttpOnly()).isTrue();
    assertThat(access.getPath()).isEqualTo("/");
  }

  @Test
  @TracesRequirement("REQ-AUTH-006")
  @DisplayName("Bearer + cookie present for different users: Bearer wins on /me")
  void bearerWinsOverCookie() {
    // Given - two users
    String bearerUser = "bearer-win@test.com";
    String cookieUser = "cookie-loser@test.com";
    String bearerToken = register(bearerUser);
    String cookieToken = register(cookieUser);

    // When - both carried, different principals
    given()
        .header("Authorization", "Bearer " + bearerToken)
        .cookie(ACCESS_COOKIE, cookieToken)
        .when()
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200)
        .body("email", org.hamcrest.Matchers.equalTo(bearerUser));
  }

  @Test
  @TracesRequirement("REQ-AUTH-007")
  @DisplayName("/me anonymous (no Bearer, no cookie) returns 401")
  void meAnonymous() {
    given().when().get("/api/v1/auth/me").then().statusCode(401);
  }

  @Test
  @TracesRequirement("REQ-AUTH-008")
  @DisplayName("logout anonymous (no cookies) returns 204 idempotently")
  void logoutAnonymous() {
    given().when().post("/api/v1/auth/logout").then().statusCode(204);
  }

  @Test
  @TracesRequirement("REQ-AUTH-002")
  @DisplayName("login/register/refresh JSON bodies still carry the exact dual-write set")
  void dualWriteJsonBodiesIntact() {
    // Given
    String email = "dualwrite@test.com";
    String registerBody =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"DW\",\"email\":\"" + email + "\",\"password\":\"password123\"}")
            .post("/api/v1/auth/register")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertThat(registerBody)
        .contains("\"token\"", "\"refreshToken\"", "\"userId\"", "\"email\"", "\"name\"");

    String loginBody =
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"" + email + "\",\"password\":\"password123\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertThat(loginBody)
        .contains("\"token\"", "\"refreshToken\"", "\"userId\"", "\"email\"", "\"name\"");

    Response login = login(email, "password123");
    String refreshBody =
        given()
            .cookie(REFRESH_COOKIE, login.getCookie(REFRESH_COOKIE))
            .contentType(ContentType.JSON)
            .post("/api/v1/auth/refresh")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertThat(refreshBody)
        .contains("\"token\"", "\"refreshToken\"", "\"userId\"", "\"email\"", "\"name\"");
  }

  @Test
  @TracesRequirement("REQ-AUTH-005")
  @DisplayName("operator BasicAuth on actuator still works (filter coexistence regression)")
  void operatorBasicAuthCoexists() {
    // Given/When - the JWT cookie filter must not interfere with the operator Basic filter
    given()
        .auth()
        .preemptive()
        .basic(OPERATOR, OPERATOR_PASSWORD)
        .when()
        .get("/actuator/health")
        .then()
        .statusCode(200);

    // And the cookie flow still works alongside it
    String email = "coexist@test.com";
    register(email);
    Response login = login(email, "password123");
    given()
        .cookie(ACCESS_COOKIE, login.getCookie(ACCESS_COOKIE))
        .when()
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200)
        .body("email", org.hamcrest.Matchers.equalTo(email));
  }

  @Test
  @TracesRequirement("REQ-AUTH-005")
  @DisplayName("Secure flag present on both cookies")
  void secureFlagOnBothCookies() {
    // Given
    String email = "secureflag@test.com";
    register(email);
    Response login = login(email, "password123");

    // Then
    assertThat(login.getDetailedCookie(ACCESS_COOKIE).isSecured()).isTrue();
    assertThat(login.getDetailedCookie(REFRESH_COOKIE).isSecured()).isTrue();
    // SameSite=Lax on both
    assertThat(login.getDetailedCookie(ACCESS_COOKIE).getSameSite()).isEqualTo("Lax");
    assertThat(login.getDetailedCookie(REFRESH_COOKIE).getSameSite()).isEqualTo("Lax");
    // HttpOnly on both
    List<String> setCookieHeaders = login.getHeaders().getValues("Set-Cookie");
    assertThat(setCookieHeaders).hasSize(2);
    assertThat(setCookieHeaders.get(0)).contains("HttpOnly").contains("Secure");
    assertThat(setCookieHeaders.get(1)).contains("HttpOnly").contains("Secure");
  }
}
