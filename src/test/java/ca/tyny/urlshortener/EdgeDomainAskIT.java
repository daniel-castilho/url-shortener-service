package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import io.restassured.RestAssured;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The edge ask endpoint against the real registry: an ACTIVE custom domain and the default host are
 * servable (200); PENDING domains and unknown hosts are not (404); token failures deny (401).
 */
@TestPropertySource(properties = "app.edge.ask-token=it-edge-token")
@DisplayName("Edge domain-ask — on-demand TLS authorization (real registry)")
class EdgeDomainAskIT extends BaseIntegrationTest {

  private static final String TOKEN = "it-edge-token";
  private static final String ACTIVE_HOST = "go.acme.io";
  private static final String PENDING_HOST = "pending.acme.io";

  @LocalServerPort private int port;

  @Autowired private CustomDomainRepositoryPort customDomainRepository;

  @Autowired private CustomDomainRegistryPort customDomainRegistry;

  @Autowired private UserRepositoryPort userRepository;

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/";

    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body("{\"name\":\"Owner\",\"email\":\"owner@test.com\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);

    String userId = userRepository.findByEmail("owner@test.com").orElseThrow().id();
    customDomainRepository.save(
        new CustomDomain(
            ACTIVE_HOST,
            userId,
            DomainStatus.ACTIVE,
            "url-shortener-verify=deadbeef",
            Instant.now()));
    customDomainRegistry.markActive(ACTIVE_HOST);

    customDomainRepository.save(
        new CustomDomain(
            PENDING_HOST, userId, DomainStatus.PENDING, "url-shortener-verify=cafe", null));
  }

  @Test
  @DisplayName("ACTIVE custom domain is servable (200)")
  void activeDomainIsServable() {
    int status =
        given()
            .queryParam("domain", ACTIVE_HOST)
            .queryParam("token", TOKEN)
            .when()
            .get("/internal/edge/domain-ask")
            .getStatusCode();
    assertThat(status).isEqualTo(200);
  }

  @Test
  @DisplayName("PENDING (claimed but unverified) domain is NOT servable (404)")
  void pendingDomainIsNotServable() {
    int status =
        given()
            .queryParam("domain", PENDING_HOST)
            .queryParam("token", TOKEN)
            .when()
            .get("/internal/edge/domain-ask")
            .getStatusCode();
    assertThat(status).isEqualTo(404);
  }

  @Test
  @DisplayName("Default host is servable (200)")
  void defaultHostIsServable() {
    int status =
        given()
            .queryParam("domain", "localhost")
            .queryParam("token", TOKEN)
            .when()
            .get("/internal/edge/domain-ask")
            .getStatusCode();
    assertThat(status).isEqualTo(200);
  }

  @Test
  @DisplayName("Unknown host is not ours (404)")
  void unknownHostIsDenied() {
    int status =
        given()
            .queryParam("domain", "not-ours.example.com")
            .queryParam("token", TOKEN)
            .when()
            .get("/internal/edge/domain-ask")
            .getStatusCode();
    assertThat(status).isEqualTo(404);
  }

  @Test
  @DisplayName("Wrong token denies (401) and missing token denies (401)")
  void tokenFailuresDeny() {
    assertThat(
            given()
                .queryParam("domain", ACTIVE_HOST)
                .queryParam("token", "wrong")
                .when()
                .get("/internal/edge/domain-ask")
                .getStatusCode())
        .isEqualTo(401);
    assertThat(
            given()
                .queryParam("domain", ACTIVE_HOST)
                .when()
                .get("/internal/edge/domain-ask")
                .getStatusCode())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("markInactive revokes edge authorization for a previously ACTIVE domain (404)")
  void inactiveDomainIsRevoked() {
    customDomainRegistry.markInactive(ACTIVE_HOST);

    int status =
        given()
            .queryParam("domain", ACTIVE_HOST)
            .queryParam("token", TOKEN)
            .when()
            .get("/internal/edge/domain-ask")
            .getStatusCode();
    assertThat(status).isEqualTo(404);
  }
}
