package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end tests for the product administration surface (ADR 0011): user listing, prefix filter,
 * cursor pagination, block/unblock, self-block, 404 semantics and the blocked-account 403 on
 * login/refresh. Private endpoints — anonymous is rejected by the security chain (401), non-admin
 * principals by the use-case layer (403).
 */
@SpringBootTest(
    classes = ca.tyny.urlshortener.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.admin-emails=admin@example.com",
      "app.security.operator.username=ops-admin-it2",
      "app.security.operator.password=it-operator-pw-16chars",
    })
@DisplayName("Admin product administration surface IT (ADR 0011)")
class AdminUsersIT extends BaseIntegrationTest {

  private static final String ADMIN_EMAIL = "admin@example.com";

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString())).thenReturn(RateLimitVerdict.allow(100));
    RestAssured.port = port;
  }

  @Test
  @DisplayName("admin lists users with role and blocked fields")
  void adminCanListUsersWithRoleAndBlockedFields() {
    String adminToken = register(ADMIN_EMAIL, "admin");

    register("alice@example.com", "Alice");
    register("bob@example.com", "Bob");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .get("/api/v1/admin/users")
        .then()
        .statusCode(200)
        .body("items", notNullValue())
        .body("items.size()", equalTo(3))
        // role = live env-list truth: the admin email is ADMIN, the rest USER
        .body("items.role", hasItems("ADMIN", "USER"))
        .body("items.findAll { it.blocked }.size()", equalTo(0));
  }

  @Test
  @DisplayName("email prefix filter returns only matching users")
  void emailPrefixFilter() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    register("alice@example.com", "Alice");
    register("bert@other.com", "Bert");

    given()
        .header("Authorization", "Bearer " + adminToken)
        .param("q", "ali")
        .get("/api/v1/admin/users")
        .then()
        .statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].email", equalTo("alice@example.com"))
        .body("items[0].role", equalTo("USER"))
        .body("items[0].blocked", equalTo(false))
        .body("items[0].createdAt", notNullValue());
  }

  @Test
  @DisplayName("cursor pagination walks all users without overlap")
  void cursorPagination() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    register("p1@example.com", "P1");
    register("p2@example.com", "P2");
    register("p3@example.com", "P3");

    io.restassured.response.Response first =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .param("limit", 2)
            .get("/api/v1/admin/users")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .body("hasMore", equalTo(true))
            .body("nextCursor", notNullValue())
            .extract()
            .response();

    String nextCursor = first.path("nextCursor");

    io.restassured.response.Response second =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .param("limit", 2)
            .param("cursor", nextCursor)
            .get("/api/v1/admin/users")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .body("hasMore", equalTo(false))
            .body("nextCursor", equalTo(null))
            .extract()
            .response();

    List<String> firstPageIds = first.path("items.userId");
    List<String> secondPageIds = second.path("items.userId");
    firstPageIds.retainAll(secondPageIds);
    assertThat(firstPageIds).isEmpty();
  }

  @Test
  @DisplayName("blocked user loses login (403 Account blocked) and block is idempotent")
  void blockedUserLosesLogin() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    Response target = registerAndExtract("target@example.com", "Target");
    String targetUserId = target.path("userId");

    block(adminToken, targetUserId).then().statusCode(204);

    // idempotent: blocking an already-blocked user answers the same 204
    block(adminToken, targetUserId).then().statusCode(204);

    login("target@example.com", "password123")
        .then()
        .statusCode(403)
        .body("error", equalTo("Forbidden"))
        .body("message", equalTo("Account blocked."));
  }

  @Test
  @DisplayName("blocked user cannot refresh its token (403 Account blocked)")
  void blockedUserCannotRefresh() {
    String adminToken = register(ADMIN_EMAIL, "admin");

    Response registered = registerAndExtract("target@example.com", "Target");
    String refreshToken = registered.path("refreshToken");

    block(adminToken, registered.path("userId")).then().statusCode(204);

    given()
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"" + refreshToken + "\"}")
        .post("/api/v1/auth/refresh")
        .then()
        .statusCode(403)
        .body("message", equalTo("Account blocked."));
  }

  @Test
  @DisplayName("self-block is rejected with 400")
  void selfBlockRejected() {
    Response admin = registerAndExtract(ADMIN_EMAIL, "admin");
    String adminToken = admin.path("token");
    String adminUserId = admin.path("userId");

    block(adminToken, adminUserId).then().statusCode(400);
    login(ADMIN_EMAIL, "password123").then().statusCode(200);
  }

  @Test
  @DisplayName("unblock restores access and unblock is idempotent")
  void unblockRestoresAccess() {
    String adminToken = register(ADMIN_EMAIL, "admin");
    Response target = registerAndExtract("target@example.com", "Target");
    String targetPassword = "password123";

    block(adminToken, target.path("userId")).then().statusCode(204);
    login("target@example.com", targetPassword).then().statusCode(403);

    unblock(adminToken, target.path("userId")).then().statusCode(204);

    login("target@example.com", targetPassword).then().statusCode(200);

    // idempotent: unblocking an unblocked user answers the same 204
    unblock(adminToken, target.path("userId")).then().statusCode(204);
  }

  @Test
  @DisplayName("blocking an unknown user answers 404")
  void blockUnknownUserAnswers404() {
    String adminToken = register(ADMIN_EMAIL, "admin");

    block(adminToken, "missing-user-id").then().statusCode(404);
    unblock(adminToken, "missing-user-id").then().statusCode(404);
  }

  @Test
  @DisplayName("non-admin principal is forbidden from the admin surface")
  void nonAdminForbidden() {
    Response user = registerAndExtract("plain@example.com", "Plain");
    Response victim = registerAndExtract("victim@example.com", "Victim");

    given()
        .header("Authorization", "Bearer " + user.path("token"))
        .get("/api/v1/admin/users")
        .then()
        .statusCode(403);

    given()
        .header("Authorization", "Bearer " + user.path("token"))
        .post("/api/v1/admin/users/" + victim.path("userId") + "/block")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("anonymous principal is rejected with 401")
  void anonymousRejected() {
    given().get("/api/v1/admin/users").then().statusCode(401);
    given().post("/api/v1/admin/users/any/block").then().statusCode(401);
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

  private Response login(String email, String password) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
        .post("/api/v1/auth/login");
  }

  private Response block(String adminToken, String userId) {
    return given()
        .header("Authorization", "Bearer " + adminToken)
        .post("/api/v1/admin/users/" + userId + "/block");
  }

  private Response unblock(String adminToken, String userId) {
    return given()
        .header("Authorization", "Bearer " + adminToken)
        .post("/api/v1/admin/users/" + userId + "/unblock");
  }
}
