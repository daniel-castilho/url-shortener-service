package ca.tyny.urlshortener;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.time.LocalDateTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("Expired URL redirect E2E Tests")
class ExpiredUrlIT extends BaseIntegrationTest {

        @LocalServerPort
        private int port;

        @MockitoBean
        private RateLimiterPort rateLimiter;

        @Autowired
        private MongoTemplate mongoTemplate;

        @BeforeEach
        void setUp() {
                when(rateLimiter.tryAcquire(any(), anyString())).thenReturn(RateLimitVerdict.allow(100));
                RestAssured.port = port;
                RestAssured.basePath = "/";
        }

        @Test
        @DisplayName("Should return 410 Gone for an expired short URL")
        void shouldReturn410ForExpiredUrl() {
                // Given
                String id = "expiredd";
                mongoTemplate.save(new ShortUrlEntity(id, "https://www.example.com/expired", "hash1234",
                                LocalDateTime.now(), null, false, 0, Instant.now().minusSeconds(60)));

                // When/Then
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/" + id)
                                .then()
                                .statusCode(410)
                                .body("error", org.hamcrest.Matchers.equalTo("URL Expired"));
        }

        @Test
        @DisplayName("Should still redirect a non-expired short URL that has an expiry")
        void shouldRedirectNonExpiredUrl() {
                // Given
                String id = "alive123";
                mongoTemplate.save(new ShortUrlEntity(id, "https://www.example.com/alive", "hash1234",
                                LocalDateTime.now(), null, false, 0, Instant.now().plusSeconds(3600)));

                // When/Then
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/" + id)
                                .then()
                                .statusCode(302)
                                .header("Location", "https://www.example.com/alive");
        }

        @Test
        @DisplayName("Should redirect a short URL before its TTL and stop after expiry")
        void shouldExpireAfterTtl() throws InterruptedException {
                // Given
                String id = given()
                                .contentType(ContentType.JSON)
                                .body(new ShortenRequest("https://www.example.com/ttl", null, 1L))
                                .when()
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                // Before expiry: redirect works
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/" + id)
                                .then()
                                .statusCode(302);

                // After the 1s TTL elapses the link must no longer redirect
                // (410 Gone via the eager expiry check, or 404 if the TTL index purged it)
                Thread.sleep(1500);
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/" + id)
                                .then()
                                .statusCode(anyOf(is(410), is(404)));
        }

        @Test
        @DisplayName("Should redirect a short URL without expiry")
        void shouldRedirectUrlWithoutExpiry() {
                // Given
                String id = "forever1";
                mongoTemplate.save(new ShortUrlEntity(id, "https://www.example.com/forever", "hash1234",
                                LocalDateTime.now(), null, false));

                // When/Then
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/" + id)
                                .then()
                                .statusCode(302)
                                .header("Location", "https://www.example.com/forever");
        }
}