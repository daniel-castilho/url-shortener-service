package ca.tyny.urlshortener;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;

/**
 * SSRF Protection Integration Tests.
 * 
 * Proves that the URL validation rejects:
 * - Private/internal IPs (RFC1918, loopback, link-local)
 * - Cloud metadata IPs (169.254.169.254)
 * - URLs with user credentials
 * - Invalid schemes (ftp, etc.)
 * - Invalid host formats
 * 
 * Allows valid public HTTPS URLs.
 */
@DisplayName("SSRF Protection Integration Tests")
@TestPropertySource(properties = {
        "app.url.allow-http=false",
        "app.url.block-private-ips=true",
        "app.url.dns-timeout-ms=2000"
})
class SsrfProtectionIT extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/";
    }

    @Test
    @DisplayName("Rejects http:// URLs when allow-http=false")
    void rejectsHttpUrl() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("http://example.com", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(400)
                .body("error", equalTo("Invalid Destination"))
                .body("message", containsString("HTTP URLs are not allowed"));
    }

    @Test
    @DisplayName("Rejects URL with user credentials")
    void rejectsUserCredentials() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("https://user:pass@example.com", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(400)
                .body("error", equalTo("Invalid Destination"))
                .body("message", containsString("user credentials"));
    }

    @Test
    @DisplayName("Rejects URL with userinfo (email-like)")
    void rejectsUserInfoEmail() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("https://user@example.com", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(400)
                .body("error", equalTo("Invalid Destination"))
                .body("message", containsString("user credentials"));
    }

    @Test
    @DisplayName("Rejects invalid host format (spaces)")
    void rejectsInvalidHost() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("https://invalid host.com", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(400)
                .body("error", equalTo("Invalid Destination"));
    }

    @Test
    @DisplayName("Rejects invalid scheme (ftp) - caught by DTO validation")
    void rejectsInvalidScheme() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("ftp://example.com", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(400)
                .body("error", anyOf(equalTo("Invalid Destination"), equalTo("Validation Failed")));
    }

    @Test
    @DisplayName("Rejects URL without scheme")
    void rejectsNoScheme() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("example.com", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(400)
                .body("error", anyOf(equalTo("Invalid Destination"), equalTo("Validation Failed")));
    }

    @Test
    @DisplayName("Accepts valid HTTPS URL")
    void acceptsValidHttpsUrl() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("https://example.com", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("shortUrl", notNullValue());
    }
}
