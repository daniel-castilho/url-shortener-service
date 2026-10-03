package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.LinkListResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortUrlResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenResponse;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Canonical public URL contract: with {@code app.shortener.public-base-url} configured, every API
 * response that carries a {@code shortUrl} must use the configured origin (full scheme + host), and
 * a custom-domain binding must win with {@code https://<domain>} regardless of configuration.
 */
@TestPropertySource(properties = "app.shortener.public-base-url=https://short.example.com")
@DisplayName("Public Base URL — canonical shortUrl in all responses")
class PublicBaseUrlIT extends BaseIntegrationTest {

  private static final String PUBLIC_BASE = "https://short.example.com";
  private static final String BOUND_HOST = "go.acme.io";

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @Autowired private CustomDomainRegistryPort customDomainRegistry;

  @Autowired private CustomDomainRepositoryPort customDomainRepository;

  @Autowired private UserRepositoryPort userRepository;

  private String token;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString()))
        .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(100));
    RestAssured.port = port;
    RestAssured.basePath = "/";

    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"User\",\"email\":\"owner@test.com\",\"password\":\"password123\"}")
        .post("/api/v1/auth/register")
        .then()
        .statusCode(200);
    token =
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"owner@test.com\",\"password\":\"password123\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .path("token");

    String userId = userRepository.findByEmail("owner@test.com").orElseThrow().id();
    customDomainRepository.save(
        new CustomDomain(
            BOUND_HOST,
            userId,
            DomainStatus.ACTIVE,
            "url-shortener-verify=deadbeef",
            Instant.now()));
    customDomainRegistry.markActive(BOUND_HOST);
  }

  @Test
  @DisplayName("POST /api/v1/urls returns shortUrl on the configured public origin")
  void shortenUsesConfiguredPublicBase() {
    ShortenResponse response = shorten(null, null);

    assertThat(response.shortUrl()).isEqualTo(PUBLIC_BASE + "/" + response.id());
    assertThat(response.shortUrl()).startsWith("https://");
  }

  @Test
  @DisplayName("GET list and detail return the same canonical shortUrl as shorten")
  void listAndDetailMatchShorten() {
    ShortenResponse created = shorten(null, null);

    LinkListResponse list =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/v1/urls")
            .then()
            .statusCode(200)
            .extract()
            .as(LinkListResponse.class);
    assertThat(list.items()).hasSize(1);
    assertThat(list.items().get(0).shortUrl()).isEqualTo(PUBLIC_BASE + "/" + created.id());

    ShortUrlResponse detail =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/v1/urls/" + created.id())
            .then()
            .statusCode(200)
            .extract()
            .as(ShortUrlResponse.class);
    assertThat(detail.shortUrl()).isEqualTo(PUBLIC_BASE + "/" + created.id());
  }

  @Test
  @DisplayName("PATCH returns the canonical shortUrl unchanged")
  void patchKeepsCanonicalShortUrl() {
    ShortenResponse created = shorten(null, null);

    ShortUrlResponse updated =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"title\":\"renamed\"}")
            .patch("/api/v1/urls/" + created.id())
            .then()
            .statusCode(200)
            .extract()
            .as(ShortUrlResponse.class);

    assertThat(updated.title()).isEqualTo("renamed");
    assertThat(updated.shortUrl()).isEqualTo(PUBLIC_BASE + "/" + created.id());
  }

  @Test
  @DisplayName("A custom-domain binding wins over the configured public base and is HTTPS")
  void customDomainBindingWins() {
    ShortenResponse created = shorten(BOUND_HOST, null);

    assertThat(created.shortUrl()).isEqualTo("https://" + BOUND_HOST + "/" + created.id());

    ShortUrlResponse detail =
        given()
            .header("Authorization", "Bearer " + token)
            .get("/api/v1/urls/" + created.id())
            .then()
            .statusCode(200)
            .extract()
            .as(ShortUrlResponse.class);
    assertThat(detail.shortUrl()).isEqualTo("https://" + BOUND_HOST + "/" + created.id());
  }

  @Test
  @DisplayName("Anonymous shorten also returns the canonical public shortUrl")
  void anonymousShortenUsesConfiguredPublicBase() {
    // Logged out: no Authorization header at all
    ShortenResponse response =
        given()
            .contentType(ContentType.JSON)
            .body("{\"originalUrl\":\"https://www.example.com/anon\"}")
            .post("/api/v1/urls")
            .then()
            .statusCode(200)
            .extract()
            .as(ShortenResponse.class);

    assertThat(response.shortUrl()).isEqualTo(PUBLIC_BASE + "/" + response.id());
  }

  @Test
  @DisplayName("A 65-char alias is rejected with 400 while a 64-char alias is accepted (E2E cap)")
  void aliasCapEnforcedEndToEnd() {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"originalUrl\":\"https://example.com/x\",\"customAlias\":\""
                + "a".repeat(65)
                + "\"}")
        .header("Authorization", "Bearer " + token)
        .post("/api/v1/urls")
        .then()
        .statusCode(400);

    ShortenResponse accepted =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"originalUrl\":\"https://example.com/x\",\"customAlias\":\""
                    + "b".repeat(64)
                    + "\"}")
            .header("Authorization", "Bearer " + token)
            .post("/api/v1/urls")
            .then()
            .statusCode(200)
            .extract()
            .as(ShortenResponse.class);

    assertThat(accepted.shortUrl()).isEqualTo(PUBLIC_BASE + "/" + "b".repeat(64));
  }

  private ShortenResponse shorten(String domain, String alias) {
    StringBuilder body = new StringBuilder("{\"originalUrl\":\"https://www.example.com/target\"");
    if (domain != null) {
      body.append(",\"domain\":\"").append(domain).append("\"");
    }
    if (alias != null) {
      body.append(",\"customAlias\":\"").append(alias).append("\"");
    }
    body.append("}");
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body.toString())
        .post("/api/v1/urls")
        .then()
        .statusCode(200)
        .extract()
        .as(ShortenResponse.class);
  }
}
