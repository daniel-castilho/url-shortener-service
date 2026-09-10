package ca.tyny.urlshortener;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import io.restassured.RestAssured;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Custom Domain Redirect (strict mirror) — Integration Tests")
class DomainRedirectIT extends BaseIntegrationTest {

  private static final String BOUND_HOST = "links.example.com";
  private static final String OTHER_HOST = "other.example.org";

  @LocalServerPort private int port;

  @MockitoBean private RateLimiterPort rateLimiter;

  @org.springframework.beans.factory.annotation.Autowired
  private ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort
      customDomainRepository;

  @org.springframework.beans.factory.annotation.Autowired
  private ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort customDomainRegistry;

  @org.springframework.beans.factory.annotation.Autowired
  private ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort urlRepository;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyString()))
        .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(100));
    RestAssured.port = port;
    RestAssured.basePath = "/";

    // ACTIVE custom domain claimed by an owner
    customDomainRepository.save(
        new CustomDomain(
            BOUND_HOST,
            "user-1",
            DomainStatus.ACTIVE,
            "url-shortener-verify=deadbeef",
            Instant.now()));
    customDomainRepository.save(
        new CustomDomain(
            OTHER_HOST,
            "user-1",
            DomainStatus.ACTIVE,
            "url-shortener-verify=deadbeef",
            Instant.now()));
    customDomainRegistry.markActive(BOUND_HOST);
    customDomainRegistry.markActive(OTHER_HOST);

    // A domain-bound link and a domain-less (default host) link
    urlRepository.save(
        new ShortUrl(
            "bound01",
            "https://target.example.com/bound",
            LocalDateTime.now(),
            "user-1",
            false,
            0,
            null,
            null,
            null,
            null,
            null,
            BOUND_HOST));
    urlRepository.save(
        new ShortUrl(
            "plain01",
            "https://target.example.com/plain",
            LocalDateTime.now(),
            "user-1",
            false,
            0,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  @Test
  @DisplayName("domain-less link redirects only on the default host")
  void domainLessLinkRedirectsOnDefaultHostOnly() {
    given()
        .redirects()
        .follow(false)
        .get("/plain01")
        .then()
        .statusCode(302)
        .header("Location", "https://target.example.com/plain");

    given()
        .redirects()
        .follow(false)
        .header("Host", BOUND_HOST)
        .get("/plain01")
        .then()
        .statusCode(404);

    given()
        .redirects()
        .follow(false)
        .header("Host", OTHER_HOST)
        .get("/plain01")
        .then()
        .statusCode(404);
  }

  @Test
  @DisplayName("bound link redirects only under its own host")
  void boundLinkRedirectsUnderOwnHostOnly() {
    given()
        .redirects()
        .follow(false)
        .header("Host", BOUND_HOST)
        .get("/bound01")
        .then()
        .statusCode(302)
        .header("Location", "https://target.example.com/bound");

    given().redirects().follow(false).get("/bound01").then().statusCode(404);

    given()
        .redirects()
        .follow(false)
        .header("Host", OTHER_HOST)
        .get("/bound01")
        .then()
        .statusCode(404);
  }

  @Test
  @DisplayName("unknown host resolves nothing")
  void unknownHostResolvesNothing() {
    given()
        .redirects()
        .follow(false)
        .header("Host", "random.example.net")
        .get("/plain01")
        .then()
        .statusCode(404);
    given()
        .redirects()
        .follow(false)
        .header("Host", "random.example.net")
        .get("/bound01")
        .then()
        .statusCode(404);
  }

  @Test
  @DisplayName("host header with port still resolves on the default host")
  void hostWithPortResolves() {
    String hostWithPort = "localhost:" + port;
    given()
        .redirects()
        .follow(false)
        .header("Host", hostWithPort)
        .get("/plain01")
        .then()
        .statusCode(302)
        .header("Location", "https://target.example.com/plain");
  }
}
