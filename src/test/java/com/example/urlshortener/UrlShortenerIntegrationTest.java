package com.example.urlshortener;

import com.example.urlshortener.core.ports.outgoing.RateLimiterPort;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.ShortenResponse;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = com.example.urlshortener.infra.Application.class)
@Testcontainers
@DisplayName("URL Shortener Integration Tests")
class UrlShortenerIntegrationTest {

        @LocalServerPort
        private int port;

        @Container
        static CassandraContainer<?> cassandra = new CassandraContainer<>(
                        DockerImageName.parse("cassandra:4.1"))
                        .withExposedPorts(9042);

        @Container
        static GenericContainer<?> redis = new GenericContainer<>(
                        DockerImageName.parse("redis:alpine"))
                        .withExposedPorts(6379)
                        .withCommand("redis-server", "--appendonly", "yes");

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.cassandra.contact-points",
                                () -> cassandra.getHost() + ":" + cassandra.getMappedPort(9042));
                registry.add("spring.cassandra.local-datacenter", () -> "datacenter1");
                registry.add("spring.cassandra.keyspace-name", () -> "url_shortener");
                registry.add("spring.cassandra.schema-action", () -> "create-if-not-exists");

                registry.add("spring.data.redis.host", redis::getHost);
                registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        }

        @MockitoBean
        private RateLimiterPort rateLimiter;

        @BeforeEach
        void setUp() {
                when(rateLimiter.isAllowed(anyString())).thenReturn(true);
                RestAssured.port = port;
                RestAssured.basePath = "/";
        }

        @Test
        @DisplayName("Should shorten URL and redirect successfully (E2E)")
        void shouldShortenAndRedirect() {
                // Given
                String originalUrl = "https://www.example.com/very/long/url/path";
                ShortenRequest request = new ShortenRequest(originalUrl);

                // When: Shorten URL
                ShortenResponse response = given()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when()
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .body("id", hasLength(greaterThanOrEqualTo(7)))
                                .body("shortUrl", startsWith("http://localhost:" + port + "/"))
                                .extract()
                                .as(ShortenResponse.class);

                assertThat(response.id()).isNotNull();
                assertThat(response.shortUrl()).contains(response.id());

                // Then: Redirect to original URL
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/" + response.id())
                                .then()
                                .statusCode(302)
                                .header("Location", originalUrl);
        }

        @Test
        @DisplayName("Should return 404 for non-existent short URL")
        void shouldReturn404ForNonExistentUrl() {
                given()
                                .redirects().follow(false)
                                .when()
                                .get("/nonexistent123")
                                .then()
                                .statusCode(404);
        }

        @Test
        @DisplayName("Should generate unique IDs for multiple URLs")
        void shouldGenerateUniqueIds() {
                // Given
                ShortenRequest request1 = new ShortenRequest("https://example1.com");
                ShortenRequest request2 = new ShortenRequest("https://example2.com");

                // When
                String id1 = given()
                                .contentType(ContentType.JSON)
                                .body(request1)
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                String id2 = given()
                                .contentType(ContentType.JSON)
                                .body(request2)
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                // Then
                assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("Should cache URLs after first access")
        void shouldCacheUrls() {
                // Given
                String originalUrl = "https://www.cached-example.com";
                ShortenRequest request = new ShortenRequest(originalUrl);

                String shortId = given()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                // When: First access (Cache Miss)
                given()
                                .redirects().follow(false)
                                .get("/" + shortId)
                                .then()
                                .statusCode(302)
                                .header("Location", originalUrl);

                // Then: Second access (Cache Hit - should still work)
                given()
                                .redirects().follow(false)
                                .get("/" + shortId)
                                .then()
                                .statusCode(302)
                                .header("Location", originalUrl);
        }

        @Test
        @DisplayName("Should handle concurrent requests correctly")
        void shouldHandleConcurrentRequests() {
                // Given
                int concurrentRequests = 50;
                java.util.Set<String> generatedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

                // When: Create 50 URLs concurrently
                java.util.stream.IntStream.range(0, concurrentRequests)
                                .parallel()
                                .forEach(i -> {
                                        String id = given()
                                                        .contentType(ContentType.JSON)
                                                        .body(new ShortenRequest("https://example.com/concurrent/" + i))
                                                        .post("/api/v1/urls")
                                                        .then()
                                                        .statusCode(200)
                                                        .extract()
                                                        .path("id");
                                        generatedIds.add(id);
                                });

                // Then: All IDs should be unique
                assertThat(generatedIds).hasSize(concurrentRequests);
        }

        @Test
        @DisplayName("Should handle URLs with special characters")
        void shouldHandleSpecialCharacters() {
                // Given
                String urlWithSpecialChars = "https://example.com/path?param=value&other=123#anchor";
                ShortenRequest request = new ShortenRequest(urlWithSpecialChars);

                // When
                String shortId = given()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                // Then: Should redirect correctly
                given()
                                .redirects().follow(false)
                                .get("/" + shortId)
                                .then()
                                .statusCode(302)
                                .header("Location", urlWithSpecialChars);
        }

        @Test
        @DisplayName("Should handle very long URLs")
        void shouldHandleVeryLongUrls() {
                // Given: URL with 2000+ characters
                String longUrl = "https://example.com/very/long/path/" + "a".repeat(2000);
                ShortenRequest request = new ShortenRequest(longUrl);

                // When
                String shortId = given()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .body("id", hasLength(greaterThanOrEqualTo(7)))
                                .extract()
                                .path("id");

                // Then: Should redirect correctly
                given()
                                .redirects().follow(false)
                                .get("/" + shortId)
                                .then()
                                .statusCode(302)
                                .header("Location", longUrl);
        }

        @Test
        @DisplayName("Should return consistent results for same URL")
        void shouldReturnConsistentResults() {
                // Given
                String url = "https://example.com/consistent";
                ShortenRequest request = new ShortenRequest(url);

                // When: Shorten same URL twice
                String id1 = given()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                String id2 = given()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .post("/api/v1/urls")
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                // Then: Both should work (may be different IDs, but both valid)
                assertThat(id1).isNotNull();
                assertThat(id2).isNotNull();

                given().redirects().follow(false).get("/" + id1).then().statusCode(302);
                given().redirects().follow(false).get("/" + id2).then().statusCode(302);
        }

        @Test
        @DisplayName("Should protect against invalid ID attempts (Bloom Filter)")
        void shouldProtectAgainstInvalidIds() {
                // Given: IDs that definitely don't exist
                String[] invalidIds = { "invalid1", "zzzzzzz", "0000000", "XXXXXXX" };

                // When/Then: All should return 404 without hitting DB (Bloom Filter protection)
                for (String invalidId : invalidIds) {
                        given()
                                        .redirects().follow(false)
                                        .get("/" + invalidId)
                                        .then()
                                        .statusCode(404);
                }
        }
}
