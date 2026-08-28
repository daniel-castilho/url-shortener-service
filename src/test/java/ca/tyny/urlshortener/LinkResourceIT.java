package ca.tyny.urlshortener;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.LinkListResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortUrlResponse;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("Links as Resource — Integration Tests")
class LinkResourceIT extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private RateLimiterPort rateLimiter;

    private String user1Token;
    private String user2Token;
    private String user1Id;
    private String user2Id;
    private String linkId;

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(any(), anyString()))
                .thenReturn(ca.tyny.urlshortener.core.model.RateLimitVerdict.allow(100));
        RestAssured.port = port;
        RestAssured.basePath = "/";
        // Register and login user1
        String user1Email = "user1@test.com";
        String user1Password = "password123";
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"User One\",\"email\":\"" + user1Email + "\"," +
                        "\"password\":\"" + user1Password + "\"}")
                .post("/api/v1/auth/register")
                .then().statusCode(200);

        user1Token = given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + user1Email + "\",\"password\":\"" + user1Password + "\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("token");

        user1Id = given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + user1Email + "\",\"password\":\"" + user1Password + "\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("userId");

        // Register and login user2
        String user2Email = "user2@test.com";
        String user2Password = "password123";
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"User Two\",\"email\":\"" + user2Email + "\"," +
                        "\"password\":\"" + user2Password + "\"}")
                .post("/api/v1/auth/register")
                .then().statusCode(200);

        user2Token = given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + user2Email + "\",\"password\":\"" + user2Password + "\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("token");

        user2Id = given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + user2Email + "\",\"password\":\"" + user2Password + "\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("userId");

        // Create a link as user1
        linkId = given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://example.com/test\"}")
                .post("/api/v1/urls")
                .then().statusCode(200)
                .extract().path("id");
    }

    // ========== GET /api/v1/urls (list) ==========

    @Test
    @DisplayName("GET /api/v1/urls - returns own links paginated")
    void list_returnsOwnLinksPaginated() {
        // Create a second link
        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://example.com/test2\"}")
                .post("/api/v1/urls")
                .then().statusCode(200);

        LinkListResponse page1 = given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls?limit=1")
                .then().statusCode(200)
                .extract().as(LinkListResponse.class);

        assertThat(page1.items()).hasSize(1);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotNull();

        LinkListResponse page2 = given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls?limit=1&cursor=" + page1.nextCursor())
                .then().statusCode(200)
                .extract().as(LinkListResponse.class);

        assertThat(page2.items()).hasSize(1);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    @DisplayName("GET /api/v1/urls - only returns own links")
    void list_onlyOwnLinks() {
        // user2 creates a link
        given()
                .header("Authorization", "Bearer " + user2Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://user2.com/link\"}")
                .post("/api/v1/urls")
                .then().statusCode(200);

        LinkListResponse page = given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls")
                .then().statusCode(200)
                .extract().as(LinkListResponse.class);

        // Should only have user1's links
        assertThat(page.items().stream().map(ShortUrlResponse::userId)).containsOnly(user1Id);
    }

    @Test
    @DisplayName("GET /api/v1/urls - unauthenticated returns 401")
    void list_unauthenticatedReturns401() {
        given()
                .get("/api/v1/urls")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("GET /api/v1/urls - malformed cursor returns 400")
    void list_malformedCursorReturns400() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls?cursor=not-valid-base64!")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("GET /api/v1/urls - limit capped at 100")
    void list_limitCapped() {
        for (int i = 0; i < 150; i++) {
            given()
                    .header("Authorization", "Bearer " + user1Token)
                    .contentType(ContentType.JSON)
                    .body("{\"originalUrl\":\"https://example.com/link" + i + "\"}")
                    .post("/api/v1/urls")
                    .then().statusCode(200);
        }

        LinkListResponse page = given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls?limit=1000")
                .then().statusCode(200)
                .extract().as(LinkListResponse.class);

        assertThat(page.items()).hasSize(100);
    }

    // ========== GET /api/v1/urls/{id} (detail) ==========

    @Test
    @DisplayName("GET /api/v1/urls/{id} - returns link details for owner")
    void get_returnsDetailsForOwner() {
        ShortUrlResponse response = given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls/" + linkId)
                .then().statusCode(200)
                .extract().as(ShortUrlResponse.class);

        assertThat(response.id()).isEqualTo(linkId);
        assertThat(response.originalUrl()).isEqualTo("https://example.com/test");
        assertThat(response.userId()).isEqualTo(user1Id);
        assertThat(response.clickCount()).isEqualTo(0L);
        assertThat(response.shortUrl()).endsWith("/" + linkId);
    }

    @Test
    @DisplayName("GET /api/v1/urls/{id} - non-owner returns 403")
    void get_nonOwnerReturns403() {
        given()
                .header("Authorization", "Bearer " + user2Token)
                .get("/api/v1/urls/" + linkId)
                .then().statusCode(403);
    }

    @Test
    @DisplayName("GET /api/v1/urls/{id} - unknown link returns 404")
    void get_unknownReturns404() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls/unknown123")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("GET /api/v1/urls/{id} - unauthenticated returns 401")
    void get_unauthenticatedReturns401() {
        given()
                .get("/api/v1/urls/" + linkId)
                .then().statusCode(401);
    }

    // ========== PATCH /api/v1/urls/{id} (update) ==========

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - updates destination without changing code")
    void patch_updatesDestinationNotCode() {
        ShortUrlResponse updated = given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://new-destination.com\"}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(200)
                .extract().as(ShortUrlResponse.class);

        assertThat(updated.id()).isEqualTo(linkId);
        assertThat(updated.originalUrl()).isEqualTo("https://new-destination.com");

        // Verify redirect goes to new destination
        given()
                .redirects().follow(false)
                .get("/" + linkId)
                .then().statusCode(302)
                .header("Location", "https://new-destination.com");
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - partial update only changes supplied fields")
    void patch_partialUpdate() {
        // First set title and tags
        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"title\":\"My Link\",\"tags\":[\"tag1\",\"tag2\"]}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(200);

        // Now update only originalUrl
        ShortUrlResponse updated = given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://another.com\"}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(200)
                .extract().as(ShortUrlResponse.class);

        assertThat(updated.originalUrl()).isEqualTo("https://another.com");
        assertThat(updated.title()).isEqualTo("My Link");
        assertThat(updated.tags()).containsExactly("tag1", "tag2");
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - expiresAt explicit null clears expiry")
    void patch_clearsExpiry() {
        // First set an expiry
        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"expiresAt\":\"2099-12-31T23:59:59Z\"}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(200);

        // Now clear it with explicit null
        ShortUrlResponse cleared = given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"expiresAt\":null}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(200)
                .extract().as(ShortUrlResponse.class);

        assertThat(cleared.expiresAt()).isNull();
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - non-owner returns 403")
    void patch_nonOwnerReturns403() {
        given()
                .header("Authorization", "Bearer " + user2Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://hacked.com\"}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(403);
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - unknown link returns 404")
    void patch_unknownReturns404() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://test.com\"}")
                .patch("/api/v1/urls/unknown123")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - archived link returns 409/400")
    void patch_archivedReturnsError() {
        // Archive the link first
        given()
                .header("Authorization", "Bearer " + user1Token)
                .delete("/api/v1/urls/" + linkId)
                .then().statusCode(204);

        // Try to update
        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://new.com\"}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(anyOf(is(400), is(409)));
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - invalid URL returns 400")
    void patch_invalidUrlReturns400() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"not-a-url\"}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(400);
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - too many tags returns 400")
    void patch_tooManyTagsReturns400() {
        List<String> manyTags = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> "\"tag" + i + "\"")
                .toList();

        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"tags\":[" + String.join(",", manyTags) + "]}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(400);
    }

    @Test
    @DisplayName("PATCH /api/v1/urls/{id} - invalid tag characters returns 400")
    void patch_invalidTagCharsReturns400() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body("{\"tags\":[\"invalid tag\"]}")
                .patch("/api/v1/urls/" + linkId)
                .then().statusCode(400);
    }

    // ========== DELETE /api/v1/urls/{id} (archive) ==========

    @Test
    @DisplayName("DELETE /api/v1/urls/{id} - archives link, returns 204")
    void delete_archivesLink() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .delete("/api/v1/urls/" + linkId)
                .then().statusCode(204);

        // Verify link is archived (detail still accessible but shows deletedAt)
        ShortUrlResponse archived = given()
                .header("Authorization", "Bearer " + user1Token)
                .get("/api/v1/urls/" + linkId)
                .then().statusCode(200)
                .extract().as(ShortUrlResponse.class);

        assertThat(archived.deletedAt()).isNotNull();
    }

    @Test
    @DisplayName("DELETE /api/v1/urls/{id} - idempotent (repeated returns 204)")
    void delete_idempotent() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .delete("/api/v1/urls/" + linkId)
                .then().statusCode(204);

        given()
                .header("Authorization", "Bearer " + user1Token)
                .delete("/api/v1/urls/" + linkId)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("DELETE /api/v1/urls/{id} - archived link redirect returns 404")
    void delete_archivedRedirectReturns404() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .delete("/api/v1/urls/" + linkId)
                .then().statusCode(204);

        // Cache eviction should ensure immediate 404
        given()
                .redirects().follow(false)
                .get("/" + linkId)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("DELETE /api/v1/urls/{id} - non-owner returns 403")
    void delete_nonOwnerReturns403() {
        given()
                .header("Authorization", "Bearer " + user2Token)
                .delete("/api/v1/urls/" + linkId)
                .then().statusCode(403);
    }

    @Test
    @DisplayName("DELETE /api/v1/urls/{id} - unknown link returns 404")
    void delete_unknownReturns404() {
        given()
                .header("Authorization", "Bearer " + user1Token)
                .delete("/api/v1/urls/unknown123")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("DELETE /api/v1/urls/{id} - unauthenticated returns 401")
    void delete_unauthenticatedReturns401() {
        given()
                .delete("/api/v1/urls/" + linkId)
                .then().statusCode(401);
    }

    // ========== 403 matrix ==========

    @Test
    @DisplayName("Single-resource endpoints - non-owner gets 403")
    void nonOwnerGets403OnAllEndpoints() {
        // get
        given().header("Authorization", "Bearer " + user2Token)
                .get("/api/v1/urls/" + linkId).then().statusCode(403);

        // patch
        given().header("Authorization", "Bearer " + user2Token)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://test.com\"}")
                .patch("/api/v1/urls/" + linkId).then().statusCode(403);

        // delete
        given().header("Authorization", "Bearer " + user2Token)
                .delete("/api/v1/urls/" + linkId).then().statusCode(403);
    }
}