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

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;

/**
 * Proves tracing is fail-open (Rule / observability spec): with the OTLP
 * endpoint pointed at an unreachable port and tracing fully enabled (sampling
 * 1.0 so spans are actually produced), shorten + redirect requests must still
 * succeed. Span export failures happen asynchronously and never block the
 * request path.
 */
@DisplayName("Tracing Fail-Open Integration Tests")
@TestPropertySource(properties = {
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.otlp.tracing.endpoint=http://localhost:59999"
})
class TracingFailOpenIT extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/";
    }

    @Test
    @DisplayName("Shorten succeeds while the OTLP collector is unreachable")
    void shortenSucceedsWithCollectorDown() {
        given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("https://example.com/no-otlp-collector", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("Redirect succeeds while the OTLP collector is unreachable")
    void redirectSucceedsWithCollectorDown() throws Exception {
        String code = given()
                .contentType(ContentType.JSON)
                .body(new ShortenRequest("https://example.com/redirect-no-otlp", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("shortUrl");

        String shortCode = URI.create(code).getPath().substring(1);

        given()
                .redirects().follow(false)
                .when()
                .get(shortCode)
                .then()
                .statusCode(302)
                .header("Location", startsWith("https://example.com/redirect-no-otlp"));
    }
}