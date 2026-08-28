package ca.tyny.urlshortener.infra.adapter.output.analytics;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickDailyDocument;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickEventDocument;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

@DisplayName("Click analytics endpoint — Integration Tests")
class ClickAnalyticsIT extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String ownerToken;
    private String otherToken;
    private String linkCode;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/";

        ownerToken = registerAndLogin("owner@test.com", "password123");
        otherToken = registerAndLogin("other@test.com", "password123");

        // Shorten a link as owner
        linkCode = given()
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(ContentType.JSON)
                .body("{\"originalUrl\":\"https://example.com/analytics\"}")
                .post("/api/v1/urls")
                .then().statusCode(200)
                .extract().path("id");

        // Populate click_daily for yesterday and day before (UTC)
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate dayBefore = yesterday.minusDays(1);

        mongoTemplate.insert(new ClickDailyDocument(linkCode, yesterday.toString(), 5L, 1L,
                Map.of("device", Map.of("mobile", 3L, "desktop", 2L),
                        "country", Map.of("BR", 4L, "(none)", 1L),
                        "referrer", Map.of("https://ref_example_com", 3L, "(none)", 2L)),
                Instant.now()), MongoCollections.CLICK_DAILY);

        mongoTemplate.insert(new ClickDailyDocument(linkCode, dayBefore.toString(), 3L, 1L,
                Map.of("device", Map.of("tablet", 3L),
                        "country", Map.of("US", 3L),
                        "referrer", Map.of("https://other_ref", 2L, "(none)", 1L)),
                Instant.now()), MongoCollections.CLICK_DAILY);

        // Also insert raw events for hourly test (last 24h)
        Instant now = Instant.now();
        Instant oneDayAgo = now.minusSeconds(86400);
        mongoTemplate.insert(new ClickEventDocument(linkCode, now, "UA-1", "203.0.113.1",
                "https://ref.example.com", "mobile", "BR"), MongoCollections.CLICK_EVENTS);
        mongoTemplate.insert(new ClickEventDocument(linkCode, now.minusSeconds(3600), "UA-2", "203.0.113.2",
                "https://ref.example.com", "desktop", "US"), MongoCollections.CLICK_EVENTS);
    }

    private String registerAndLogin(String email, String password) {
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"User\",\"email\":\"" + email + "\"," +
                        "\"password\":\"" + password + "\"}")
                .post("/api/v1/auth/register")
                .then().statusCode(200);
        return given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("token");
    }

    @Test
    @DisplayName("Owner reads daily series + breakdown")
    void ownerReadsDailySeries() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate dayBefore = yesterday.minusDays(1);

        Map<String, Object> resp = given()
                .header("Authorization", "Bearer " + ownerToken)
                .param("unit", "day")
                .param("from", dayBefore.toString())
                .param("to", yesterday.toString())
                .get("/api/v1/urls/" + linkCode + "/clicks")
                .then()
                .statusCode(200)
                .extract().as(Map.class);

        assertThat(resp.get("id")).isEqualTo(linkCode);
        assertThat(resp.get("unit")).isEqualTo("day");
        assertThat(((Number) resp.get("totalClicks")).longValue()).isEqualTo(8);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) resp.get("series");
        assertThat(series).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> breakdown = (Map<String, Map<String, Number>>) resp.get("breakdown");
        assertThat(breakdown).isNotNull();
        assertThat(breakdown.get("device")).containsEntry("mobile", 3);
        assertThat(breakdown.get("device")).containsEntry("desktop", 2);
        assertThat(breakdown.get("device")).containsEntry("tablet", 3);
        assertThat(breakdown.get("referrer")).containsEntry("https://ref_example_com", 3);
        assertThat(breakdown.get("referrer")).containsEntry("https://other_ref", 2);
    }

    @Test
    @DisplayName("Owner reads daily series with unique visitor counts")
    void ownerReadsDailySeriesWithUnique() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate dayBefore = yesterday.minusDays(1);

        // Populate HLL with 3 unique IPs for yesterday, 2 for day before
        String hllKey1 = "hll:clicks:" + linkCode + ":" + yesterday;
        String hllKey2 = "hll:clicks:" + linkCode + ":" + dayBefore;
        redisTemplate.opsForHyperLogLog().add(hllKey1, "203.0.113.1", "203.0.113.2", "203.0.113.3");
        redisTemplate.opsForHyperLogLog().add(hllKey2, "203.0.113.4", "203.0.113.5");

        Map<String, Object> resp = given()
                .header("Authorization", "Bearer " + ownerToken)
                .param("unit", "day")
                .param("from", dayBefore.toString())
                .param("to", yesterday.toString())
                .get("/api/v1/urls/" + linkCode + "/clicks")
                .then()
                .statusCode(200)
                .extract().as(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Long> unique = (Map<String, Long>) resp.get("uniquePerBucket");
        assertThat(unique).isNotNull();
        assertThat(((Number) unique.get(yesterday.toString())).longValue()).isEqualTo(3);
        assertThat(((Number) unique.get(dayBefore.toString())).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("Owner reads hourly series (bounded range)")
    void ownerReadsHourlySeries() {
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);

        given()
                .header("Authorization", "Bearer " + ownerToken)
                .param("unit", "hour")
                .param("from", from.toString())
                .param("to", to.toString())
                .get("/api/v1/urls/" + linkCode + "/clicks")
                .then()
                .statusCode(200)
                .body("unit", equalTo("hour"))
                .body("totalClicks", equalTo(2))
                .body("series.size()", equalTo(2))
                .body("series[0].time", notNullValue());
    }

    @Test
    @DisplayName("Hourly range wider than 30 days → 400")
    void hourlyRangeTooWide() {
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(31);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);

        given()
                .header("Authorization", "Bearer " + ownerToken)
                .param("unit", "hour")
                .param("from", from.toString())
                .param("to", to.toString())
                .get("/api/v1/urls/" + linkCode + "/clicks")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("Invalid unit → 400")
    void invalidUnit() {
        given()
                .header("Authorization", "Bearer " + ownerToken)
                .param("unit", "invalid")
                .get("/api/v1/urls/" + linkCode + "/clicks")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("Non-owner → 403")
    void nonOwnerForbidden() {
        given()
                .header("Authorization", "Bearer " + otherToken)
                .param("unit", "day")
                .get("/api/v1/urls/" + linkCode + "/clicks")
                .then()
                .statusCode(403);
    }

    @Test
    @DisplayName("Unauthenticated → 401")
    void unauthenticated() {
        given()
                .param("unit", "day")
                .get("/api/v1/urls/" + linkCode + "/clicks")
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("Unknown link → 404")
    void unknownLink() {
        given()
                .header("Authorization", "Bearer " + ownerToken)
                .param("unit", "day")
                .get("/api/v1/urls/unknown999/clicks")
                .then()
                .statusCode(404);
    }
}