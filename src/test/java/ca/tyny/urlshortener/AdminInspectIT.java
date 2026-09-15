package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end tests for the read-only admin inspection surface (story 10.3, ADR 0011): listing any
 * user's links (including archived, deletedAt visible) and global lookup of a short code with
 * ownership metadata (ownerEmail nullable when the owner document is gone).
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.admin-emails=admin@example.com",
      "app.security.operator.username=ops-admin-it3",
      "app.security.operator.password=it-operator-pw-16chars",
    })
@DisplayName("Admin read-only inspection IT (10.3)")
class AdminInspectIT extends BaseIntegrationTest {

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
      "admin inspects a user's links including archived (deletedAt visible) and owner list")
  void adminInspectsUserUrlsIncludingArchived() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    String userToken = register("inspect@example.com", "Insp");
    String userId = userIdOf("inspect@example.com");

    String liveCode = shorten(userToken, "https://example.com/live");
    String archivedCode = shorten(userToken, "https://example.com/archived");
    archive(userToken, archivedCode);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .get("/api/v1/admin/users/" + userId + "/urls")
        .then()
        .statusCode(200)
        .body("items.size()", equalTo(2))
        .body("items.id", org.hamcrest.Matchers.hasItems(liveCode, archivedCode));

    // archived link keeps deletedAt visible; live link has deletedAt null
    given()
        .header("Authorization", "Bearer " + adminToken)
        .get("/api/v1/admin/users/" + userId + "/urls")
        .then()
        .statusCode(200)
        .body("items.findAll { it.id == '" + archivedCode + "' }[0].deletedAt", notNullValue())
        .body("items.findAll { it.id == '" + liveCode + "' }[0].deletedAt", nullValue());
  }

  @Test
  @DisplayName("admin list of a user's urls is cursor-paginated without overlap")
  void adminListUserUrlsPagination() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    String userToken = register("paging@example.com", "Paging");
    String userId = userIdOf("paging@example.com");

    shorten(userToken, "https://example.com/a");
    shorten(userToken, "https://example.com/b");
    shorten(userToken, "https://example.com/c");

    Response first =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .param("limit", 2)
            .get("/api/v1/admin/users/" + userId + "/urls")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .body("hasMore", equalTo(true))
            .body("nextCursor", notNullValue())
            .extract()
            .response();

    String nextCursor = first.path("nextCursor");

    Response second =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .param("limit", 2)
            .param("cursor", nextCursor)
            .get("/api/v1/admin/users/" + userId + "/urls")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("hasMore", equalTo(false))
            .body("nextCursor", nullValue())
            .extract()
            .response();

    List<String> firstPageIds = first.path("items.id");
    List<String> secondPageIds = second.path("items.id");
    firstPageIds.retainAll(secondPageIds);
    org.assertj.core.api.Assertions.assertThat(firstPageIds).isEmpty();
  }

  @Test
  @DisplayName("admin list of urls of an unknown user answers 404")
  void adminListUserUrlsUnknownUserIs404() {
    String adminToken = register(ADMIN_EMAIL, "admin");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .get("/api/v1/admin/users/missing-user/urls")
        .then()
        .statusCode(404);
  }

  @Test
  @DisplayName("admin looks up a short URL by code with correct ownership")
  void adminLooksUpUrlByCode() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    String userToken = register("owner@example.com", "Owner");
    String userId = userIdOf("owner@example.com");
    String code = shorten(userToken, "https://example.com/lookup");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .param("code", code)
        .get("/api/v1/admin/urls")
        .then()
        .statusCode(200)
        .body("item.id", equalTo(code))
        .body("item.originalUrl", equalTo("https://example.com/lookup"))
        .body("ownerUserId", equalTo(userId))
        .body("ownerEmail", equalTo("owner@example.com"));
  }

  @Test
  @DisplayName("admin lookup of an unknown code answers 404")
  void adminLookupUnknownCodeIs404() {
    String adminToken = register(ADMIN_EMAIL, "admin");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .param("code", "zzzzzzz")
        .get("/api/v1/admin/urls")
        .then()
        .statusCode(404);
  }

  @Test
  @DisplayName("lookup keeps ownerUserId and returns null ownerEmail when the owner is gone")
  void adminLookupReturnsNullOwnerEmailWhenOwnerMissing() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    String userToken = register("ghost@example.com", "Ghost");
    String userId = userIdOf("ghost@example.com");
    String code = shorten(userToken, "https://example.com/ghost-url");

    userRepository.deleteById(userId);

    given()
        .header("Authorization", "Bearer " + adminToken)
        .param("code", code)
        .get("/api/v1/admin/urls")
        .then()
        .statusCode(200)
        .body("ownerUserId", equalTo(userId))
        .body("ownerEmail", nullValue());
  }

  @Test
  @DisplayName("non-admin is forbidden and anonymous is rejected on the inspection endpoints")
  void securityOnInspectionEndpoints() {
    String plainToken = register("plain2@example.com", "Plain");

    given()
        .header("Authorization", "Bearer " + plainToken)
        .get("/api/v1/admin/users/whatever/urls")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", "Bearer " + plainToken)
        .param("code", "abc123")
        .get("/api/v1/admin/urls")
        .then()
        .statusCode(403);

    given().get("/api/v1/admin/users/whatever/urls").then().statusCode(401);
    given().param("code", "abc123").get("/api/v1/admin/urls").then().statusCode(401);
  }

  // --- helpers ---

  private String register(String email, String name) {
    return registerAndExtract(email, name).path("token");
  }

  private String userIdOf(String email) {
    return userRepository.findByEmail(email).orElseThrow().id();
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

  private void archive(String token, String id) {
    given()
        .header("Authorization", "Bearer " + token)
        .delete("/api/v1/urls/" + id)
        .then()
        .statusCode(204);
  }
}
