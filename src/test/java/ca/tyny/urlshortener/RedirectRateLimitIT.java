package ca.tyny.urlshortener;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Proves the redirect hot path is rate limited per IP (anti-enumeration,
 * Rule 5) against the real Redis token bucket: with redirect capacity 3, the
 * first three redirects are 302 and everything after — including probes for
 * unknown codes — is 429 with Retry-After. The shorten scope keeps its own
 * budget (scope isolation). BaseIntegrationTest flushes Redis between tests,
 * resetting buckets.
 */
@DisplayName("Redirect Rate Limit Integration Tests")
@TestPropertySource(properties = {
        "rate-limiter.redirect-limit=3",
        "rate-limiter.redirect-window=PT1M",
        "rate-limiter.limit=5",
        "rate-limiter.window=PT1M"
})
class RedirectRateLimitIT extends BaseIntegrationTest {

    private static final String TEST_URL = "https://www.example.com/rate-limited";

    @LocalServerPort
    private int port;

    @Autowired
    private UrlRepositoryPort urlRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/";
        urlRepository.save(new ShortUrl("ratelim1", TEST_URL, LocalDateTime.now()));
    }

    @Test
    @DisplayName("Allows up to capacity then blocks redirects with Retry-After")
    void throttlesAfterCapacity() {
        for (int i = 0; i < 3; i++) {
            given()
                    .redirects().follow(false)
                    .when()
                    .get("/ratelim1")
                    .then()
                    .statusCode(302)
                    .header("Location", equalTo(TEST_URL));
        }

        given()
                .redirects().follow(false)
                .when()
                .get("/ratelim1")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue());
    }

    @Test
    @DisplayName("Throttles unknown-code probing too (anti-enumeration)")
    void throttlesUnknownCodeProbing() {
        for (int i = 0; i < 3; i++) {
            given()
                    .redirects().follow(false)
                    .when()
                    .get("/nope" + i + "xx")
                    .then()
                    .statusCode(404);
        }

        // Budget exhausted by probing: even a valid code is now blocked,
        // and further probes stay 429 instead of leaking 404 vs 429 timing
        given()
                .redirects().follow(false)
                .when()
                .get("/ratelim1")
                .then()
                .statusCode(429);
        given()
                .redirects().follow(false)
                .when()
                .get("/nope99999")
                .then()
                .statusCode(429);
    }

    @Test
    @DisplayName("Shorten scope keeps its own budget when redirect is exhausted")
    void scopesAreIsolated() {
        for (int i = 0; i < 3; i++) {
            given().redirects().follow(false).get("/ratelim1").then().statusCode(302);
        }
        given().redirects().follow(false).get("/ratelim1").then().statusCode(429);

        given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(new ShortenRequest("https://example.com/isolated", null))
                .when()
                .post("/api/v1/urls")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("Concurrent burst admits exactly the capacity")
    void concurrentBurstAdmitsExactlyCapacity() throws Exception {
        int threads = 12;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger redirects = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    int status = given()
                            .redirects().follow(false)
                            .when()
                            .get("/ratelim1")
                            .statusCode();
                    if (status == 302) {
                        redirects.incrementAndGet();
                    } else if (status == 429) {
                        blocked.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(redirects.get()).isEqualTo(3);
        assertThat(blocked.get()).isEqualTo(threads - 3);
    }
}
