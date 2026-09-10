package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Domain Binding via shorten and PATCH — Integration Tests")
class DomainBindingIT extends BaseIntegrationTest {

  private static final String BOUND_HOST = "links.example.com";

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @org.springframework.beans.factory.annotation.Autowired
  private CustomDomainRepositoryPort customDomainRepository;

  @org.springframework.beans.factory.annotation.Autowired
  private CustomDomainRegistryPort customDomainRegistry;

  @org.springframework.beans.factory.annotation.Autowired
  private ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort userRepository;

  private String user1Token;
  private String user2Token;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString()))
        .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(100));
    RestAssured.port = port;
    RestAssured.basePath = "/";
    user1Token = registerAndLogin("user1@test.com", "password123");
    user2Token = registerAndLogin("user2@test.com", "password123");

    String user1Id =
        userRepository
            .findByEmail("user1@test.com")
            .map(ca.tyny.urlshortener.core.model.User::id)
            .orElseThrow();

    customDomainRepository.save(
        new CustomDomain(
            BOUND_HOST,
            user1Id,
            DomainStatus.ACTIVE,
            "url-shortener-verify=deadbeef",
            Instant.now()));
    customDomainRegistry.markActive(BOUND_HOST);
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

  private String shorten(String token, String url, String domain, int expectedStatus) {
    String body =
        "{\"originalUrl\":\""
            + url
            + "\""
            + (domain != null ? ",\"domain\":\"" + domain + "\"" : "")
            + "}";
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/urls")
        .then()
        .statusCode(expectedStatus)
        .extract()
        .path("id");
  }

  @Test
  @DisplayName(
      "shorten with an owned verified domain: link bound and redirect resolves under the host only")
  void shortenWithOwnedDomainBindsAndRedirects() {
    String id = shorten(user1Token, "https://target.example.com/campaign", BOUND_HOST, 200);

    String domainInResponse =
        given()
            .header("Authorization", "Bearer " + user1Token)
            .get("/api/v1/urls/" + id)
            .then()
            .statusCode(200)
            .extract()
            .path("domain");
    assertThat(domainInResponse).isEqualTo(BOUND_HOST);

    given()
        .redirects()
        .follow(false)
        .header("Host", BOUND_HOST)
        .get("/" + id)
        .then()
        .statusCode(302)
        .header("Location", "https://target.example.com/campaign");

    given().redirects().follow(false).get("/" + id).then().statusCode(404);
  }

  @Test
  @DisplayName("shorten with another user's verified domain is 403")
  void shortenWithForeignDomainIsForbidden() {
    given()
        .header("Authorization", "Bearer " + user2Token)
        .contentType(ContentType.JSON)
        .body(
            "{\"originalUrl\":\"https://target.example.com/x\",\"domain\":\"" + BOUND_HOST + "\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("shorten with an unverified domain is 400")
  void shortenWithPendingDomainIsBadRequest() {
    String user1Id =
        userRepository
            .findByEmail("user1@test.com")
            .map(ca.tyny.urlshortener.core.model.User::id)
            .orElseThrow();
    customDomainRepository.save(
        new CustomDomain(
            "pending.example.com",
            user1Id,
            DomainStatus.PENDING,
            "url-shortener-verify=deadbeef",
            Instant.now()));

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body(
            "{\"originalUrl\":\"https://target.example.com/x\",\"domain\":\"pending.example.com\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(400)
        .body("error", equalTo("Domain Not Verified"));
  }

  @Test
  @DisplayName("shorten with an unclaimed domain is 400")
  void shortenWithUnclaimedDomainIsBadRequest() {
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body(
            "{\"originalUrl\":\"https://target.example.com/x\",\"domain\":\"nobody.example.com\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(400)
        .body("error", equalTo("Invalid Domain"));
  }

  @Test
  @DisplayName("anonymous shorten with a domain is rejected")
  void anonymousShortenWithDomainIsRejected() {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"originalUrl\":\"https://target.example.com/x\",\"domain\":\"" + BOUND_HOST + "\"}")
        .post("/api/v1/urls")
        .then()
        .statusCode(400);
  }

  @Test
  @DisplayName("PATCH can bind a domain to a link and clear it again")
  void patchBindsAndClearsDomain() {
    String id = shorten(user1Token, "https://target.example.com/plain", null, 200);

    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"domain\":\"" + BOUND_HOST + "\"}")
        .patch("/api/v1/urls/" + id)
        .then()
        .statusCode(200)
        .body("domain", equalTo(BOUND_HOST));

    given()
        .redirects()
        .follow(false)
        .header("Host", BOUND_HOST)
        .get("/" + id)
        .then()
        .statusCode(302)
        .header("Location", "https://target.example.com/plain");

    // Clear the binding: back to default-host only
    given()
        .header("Authorization", "Bearer " + user1Token)
        .contentType(ContentType.JSON)
        .body("{\"domain\":null}")
        .patch("/api/v1/urls/" + id)
        .then()
        .statusCode(200)
        .body("domain", nullValue());

    given()
        .redirects()
        .follow(false)
        .get("/" + id)
        .then()
        .statusCode(302)
        .header("Location", "https://target.example.com/plain");

    given()
        .redirects()
        .follow(false)
        .header("Host", BOUND_HOST)
        .get("/" + id)
        .then()
        .statusCode(404);
  }
}
