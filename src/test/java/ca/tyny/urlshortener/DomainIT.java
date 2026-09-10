package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.ports.outgoing.VerificationTokenPort;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.DomainListResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.DomainResponse;
import ca.tyny.urlshortener.infra.adapter.output.dns.DnsTxtResolver;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Custom Domains — Integration Tests")
class DomainIT extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @MockitoBean private VerificationTokenPort verificationTokenPort;

  @MockitoBean private DnsTxtResolver dnsTxtResolver;

  private String user1Token;
  private String user2Token;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString()))
        .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(100));
    when(verificationTokenPort.generateToken()).thenReturn("url-shortener-verify=deadbeef");
    RestAssured.port = port;
    RestAssured.basePath = "/";
    user1Token = registerAndLogin("user1@test.com", "password123");
    user2Token = registerAndLogin("user2@test.com", "password123");
  }

  private String registerAndLogin(String email, String password) {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"User\",\"email\":\""
                + email
                + "\","
                + "\"password\":\""
                + password
                + "\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .path("token");
  }

  @Test
  @DisplayName("POST /api/v1/domains - claims a host with a verification token (PENDING)")
  void claim_returnsPendingWithToken() {
    DomainResponse response =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .contentType(ContentType.JSON)
            .body("{\"host\":\"links.example.com\"}")
            .post("/api/v1/domains")
            .then()
            .statusCode(201)
            .extract()
            .as(DomainResponse.class);

    assertThat(response.host()).isEqualTo("links.example.com");
    assertThat(response.status()).isEqualTo(DomainStatus.PENDING);
    assertThat(response.verificationToken()).isEqualTo("url-shortener-verify=deadbeef");
  }

  @Test
  @DisplayName("POST /api/v1/domains - duplicate host is 409")
  void claim_duplicateIsConflict() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(409);
  }

  @Test
  @DisplayName("POST /api/v1/domains - malformed or default host is 400")
  void claim_invalidHostIsBadRequest() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"localhost\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(400);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"not a host\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(400);
  }

  @Test
  @DisplayName("POST /api/v1/domains - unauthenticated is 401")
  void claim_unauthenticatedIsUnauthorized() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(401);
  }

  @Test
  @DisplayName("GET /api/v1/domains - lists only the caller's domains")
  void list_returnsOwnDomains() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);
    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.org\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);

    DomainListResponse user1 =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/v1/domains")
            .then()
            .statusCode(200)
            .extract()
            .as(DomainListResponse.class);

    assertThat(user1.domains())
        .extracting(DomainResponse::host)
        .containsExactly("links.example.com");
  }

  @Test
  @DisplayName("POST /api/v1/domains/{host}/verify - token found promotes to ACTIVE")
  void verify_promotesToActiveWhenTxtMatches() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);
    when(dnsTxtResolver.resolveTxt("links.example.com"))
        .thenReturn(List.of("url-shortener-verify=deadbeef"));

    DomainResponse response =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .post("/api/v1/domains/links.example.com/verify")
            .then()
            .statusCode(200)
            .extract()
            .as(DomainResponse.class);

    assertThat(response.status()).isEqualTo(DomainStatus.ACTIVE);
  }

  @Test
  @DisplayName("POST /api/v1/domains/{host}/verify - missing record marks FAILED")
  void verify_marksFailedWhenTxtMissing() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);
    when(dnsTxtResolver.resolveTxt("links.example.com")).thenReturn(List.of());

    DomainResponse response =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .post("/api/v1/domains/links.example.com/verify")
            .then()
            .statusCode(200)
            .extract()
            .as(DomainResponse.class);

    assertThat(response.status()).isEqualTo(DomainStatus.FAILED);
  }

  @Test
  @DisplayName("POST /api/v1/domains/{host}/verify - foreign domain is 403")
  void verify_foreignDomainIsForbidden() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", "Bearer " + user2Token)
        .post("/api/v1/domains/links.example.com/verify")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("DELETE /api/v1/domains/{host} - owner removes the claim")
  void delete_removesOwnedHost() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .delete("/api/v1/domains/links.example.com")
        .then()
        .statusCode(204);

    DomainListResponse empty =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/v1/domains")
            .then()
            .statusCode(200)
            .extract()
            .as(DomainListResponse.class);
    assertThat(empty.domains()).isEmpty();
  }

  @Test
  @DisplayName("DELETE /api/v1/domains/{host} - unknown host is 404, foreign is 403")
  void delete_unknownIsNotFoundForeignIsForbidden() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .delete("/api/v1/domains/links.example.com")
        .then()
        .statusCode(404);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"host\":\"links.example.com\"}")
        .post("/api/v1/domains")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", "Bearer " + user2Token)
        .delete("/api/v1/domains/links.example.com")
        .then()
        .statusCode(403);
  }
}
