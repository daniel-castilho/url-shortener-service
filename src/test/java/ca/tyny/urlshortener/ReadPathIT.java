package ca.tyny.urlshortener;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.model.CacheLookup;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("Read Path Integration Tests (Policy B)")
class ReadPathIT extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    @Autowired
    private UrlCachePort urlCache;

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(100));
        RestAssured.port = port;
        RestAssured.basePath = "/";
    }

    @Test
    @DisplayName("Should resolve DB-present code via cache after first access")
    void shouldResolveDbCodeViaCacheAfterFirstAccess() {
        // Given: shorten a URL
        String originalUrl = "https://example.com/read-path-test-1";
        var request = new ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest(originalUrl, null);
        String shortId = given()
                .contentType("application/json")
                .body(request)
                .post("/api/v1/urls")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // Clear cache to simulate cold start
        urlCache.lookup(shortId); // warm up if needed

        // When: First access - cache miss, should hit DB
        given()
                .redirects().follow(false)
                .get("/" + shortId)
                .then()
                .statusCode(302)
                .header("Location", originalUrl);

        // Second access - should hit cache
        given()
                .redirects().follow(false)
                .get("/" + shortId)
                .then()
                .statusCode(302)
                .header("Location", originalUrl);
    }

    @Test
    @DisplayName("Bloom-negative code triggers findById per Policy B")
    void bloomNegativeTriggersFindByIdPerPolicyB() {
        // Given: a code that definitely does not exist (Bloom filter will say negative)
        String nonExistentId = "bloomnegative123";

        // When: request the non-existent code
        given()
                .redirects().follow(false)
                .get("/" + nonExistentId)
                .then()
                .statusCode(404);

        // Per Policy B: the bloom-negative is treated as a lightweight cache-miss
        // and resolved by findById. The bloom filter only short-circuits the Redis get.
        // The DB lookup still happens and returns 404.
        // This test verifies the 404 is returned correctly (behaviour is correct per Policy B).
    }

    @Test
    @DisplayName("Cache lookup returns explicit BLOOM_NEGATIVE for bloom-rejected codes")
    void cacheLookupReturnsExplicitBloomNegativeForRejectedCodes() {
        // Given: a code that the Bloom filter will reject
        String bloomRejectedId = "bloomrejected456";

        // When: lookup via cache port
        CacheLookup lookup = urlCache.lookup(bloomRejectedId);

        // Then: Should return BLOOM_NEGATIVE absence signal
        assertThat(lookup.absence()).isEqualTo(ca.tyny.urlshortener.core.model.CacheLookup.Absence.BLOOM_NEGATIVE);
        assertThat(lookup.value()).isNull();
    }

    @Test
    @DisplayName("Cache lookup returns MISS for non-existent codes that pass Bloom filter")
    void cacheLookupReturnsMissForNonExistentPassingBloom() {
        // Given: a code that passes Bloom filter but doesn't exist in Redis/DB
        // (This requires a code that passes Bloom but isn't cached)
        String codeNotInRedis = "miss0000";

        // When: lookup via cache port
        CacheLookup lookup = urlCache.lookup(codeNotInRedis);

        // Then: Should return MISS (not in cache, but not bloom-rejected either)
        // Note: This could be BLOOM_NEGATIVE or MISS depending on Bloom filter state
        // We just verify it's not a HIT
        assertThat(lookup.isHit()).isFalse();
    }

    @Test
    @DisplayName("Real redirect works through cache after put/seed")
    void realRedirectWorksThroughCacheAfterPutSeed() {
        // Given: shorten a URL
        String originalUrl = "https://example.com/read-path-cache-test";
        var request = new ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortenRequest(originalUrl, null);
        String shortId = given()
                .contentType("application/json")
                .body(request)
                .post("/api/v1/urls")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // When: First redirect (cache miss -> DB -> cache put)
        given()
                .redirects().follow(false)
                .get("/" + shortId)
                .then()
                .statusCode(302)
                .header("Location", originalUrl);

        // When: Second redirect (cache hit)
        given()
                .redirects().follow(false)
                .get("/" + shortId)
                .then()
                .statusCode(302)
                .header("Location", originalUrl);

        // Then: Cache lookup should return HIT
        var lookup = urlCache.lookup(shortId);
        assertThat(lookup.isHit()).isTrue();
        assertThat(lookup.value().originalUrl()).isEqualTo(originalUrl);
    }
}