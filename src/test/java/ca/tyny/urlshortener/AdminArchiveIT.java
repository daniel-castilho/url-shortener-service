package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end tests for the admin force-archive (story 10.4, ADR 0011) and the write-path block
 * check: a blocked account cannot shorten (403 before any side effect) but can still read; the
 * anonymous shorten path is unaffected (block is per-account, not per-IP).
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.admin-emails=admin@example.com",
      "app.security.operator.username=ops-admin-it4",
      "app.security.operator.password=it-operator-pw-16chars",
    })
@DisplayName("Admin force archive + write-path block check IT (10.4)")
class AdminArchiveIT extends BaseIntegrationTest {

  private static final String ADMIN_EMAIL = "admin@example.com";

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @Autowired private UserRepositoryPort userRepository;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString())).thenReturn(RateLimitVerdict.allow(100));
    RestAssured.port = port;
  }

  @Test
  @DisplayName(
      "force archive sequence: 204 -> redirect 404 -> idempotent 204 -> owner list deletedAt")
  void forceArchiveSequence() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    String userToken = register("arch@example.com", "Arch");

    String code = shorten(userToken, "https://example.com/to-archive");

    // public redirect works before the archive
    given().redirects().follow(false).get("/" + code).then().statusCode(302);

    // force archive -> 204, then the public redirect answers 404
    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete("/api/v1/admin/urls/" + code)
        .then()
        .statusCode(204);
    given().redirects().follow(false).get("/" + code).then().statusCode(404);

    // owner still sees the link, now with deletedAt set
    given()
        .header("Authorization", "Bearer " + userToken)
        .get("/api/v1/urls")
        .then()
        .statusCode(200)
        .body("items.findAll { it.id == '" + code + "' }.size()", equalTo(1))
        .body("items.findAll { it.id == '" + code + "' }[0].deletedAt", notNullValue());

    // idempotent: archiving an already-archived link answers the same 204
    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete("/api/v1/admin/urls/" + code)
        .then()
        .statusCode(204);

    // unknown link -> 404
    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete("/api/v1/admin/urls/zzzzzzz")
        .then()
        .statusCode(404);
  }

  @Test
  @DisplayName("blocked account cannot shorten (403) but can read; anonymous stays allowed")
  void blockedUserCannotShortenButCanRead() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    Response user = registerAndExtract("write@example.com", "Write");
    String userToken = user.path("token");
    String userId = user.path("userId");

    // pre-block token shortens fine (baseline)
    given()
        .header("Authorization", "Bearer " + userToken)
        .contentType(ContentType.JSON)
        .body("{\"originalUrl\":\"https://example.com/before-block\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(200);

    block(adminToken, userId).then().statusCode(204);

    // same pre-block token: POST /urls -> 403, nothing created (no side effect)
    given()
        .header("Authorization", "Bearer " + userToken)
        .contentType(ContentType.JSON)
        .body("{\"originalUrl\":\"https://example.com/after-block\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(403)
        .body("message", equalTo("Account blocked."));

    // reads are not blocked in v1: the same token lists and reads its links
    given()
        .header("Authorization", "Bearer " + userToken)
        .get("/api/v1/urls")
        .then()
        .statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].originalUrl", equalTo("https://example.com/before-block"));

    // anonymous shorten is unaffected by an account block (block is per-account, not per-IP)
    given()
        .contentType(ContentType.JSON)
        .body("{\"originalUrl\":\"https://example.com/anonymous\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(200);
  }

  @Test
  @DisplayName("non-admin is forbidden and anonymous is rejected on force archive")
  void securityOnForceArchive() {
    String userToken = register("guard@example.com", "Guard");
    String adminToken = register(ADMIN_EMAIL, "admin");
    String code = shorten(userToken, "https://example.com/guarded");

    given()
        .header("Authorization", "Bearer " + userToken)
        .delete("/api/v1/admin/urls/" + code)
        .then()
        .statusCode(403);
    given().delete("/api/v1/admin/urls/" + code).then().statusCode(401);

    // untouched by the forbidden attempts
    given().redirects().follow(false).get("/" + code).then().statusCode(302);
  }

  @Test
  @DisplayName("force archive of an unknown link answers 404")
  void forceArchiveUnknownLinkIs404() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete("/api/v1/admin/urls/missing")
        .then()
        .statusCode(404);
  }

  // --- helpers ---

  private String register(String email, String name) {
    return registerAndExtract(email, name).path("token");
  }

  private Response registerAndExtract(String email, String name) {
    String password = "password123";
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\""
                + name
                + "\",\"email\":\""
                + email
                + "\",\"password\":\""
                + password
                + "\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  private String shorten(String token, String originalUrl) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"originalUrl\":\"" + originalUrl + "\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(200)
        .extract()
        .path("id");
  }

  private Response block(String adminToken, String userId) {
    return given()
        .header("Authorization", "Bearer " + adminToken)
        .post("/api/v1/admin/users/" + userId + "/block");
  }
}
