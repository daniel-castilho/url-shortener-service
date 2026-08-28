package ca.tyny.urlshortener.infra.adapter.output.analytics;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickDailyDocument;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickEventDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Click daily rollup integration tests")
class ClickDailyRollupIT extends BaseIntegrationTest {

    @Autowired
    private ClickDailyRollupJob rollupJob;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    @DisplayName("Aggregates the previous UTC day into click_daily with breakdown")
    void aggregatesYesterday() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Instant start = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant mid = start.plusSeconds(3600);
        Instant end = yesterday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // 3 clicks on code "aaa111": 2 mobile (1 BR, 1 no country), 1 desktop
        mongoTemplate.insert(new ClickEventDocument("aaa111", start, "UA-1", "203.0.113.1",
                "https://ref.example.com", "mobile", "BR"), MongoCollections.CLICK_EVENTS);
        mongoTemplate.insert(new ClickEventDocument("aaa111", mid, "UA-2", "203.0.113.2",
                "https://ref.example.com", "mobile", null), MongoCollections.CLICK_EVENTS);
        mongoTemplate.insert(new ClickEventDocument("aaa111", end.minusSeconds(60), "UA-3", "203.0.113.3",
                null, "desktop", "US"), MongoCollections.CLICK_EVENTS);
        // A different day must NOT be picked up
        mongoTemplate.insert(new ClickEventDocument("aaa111", Instant.now(), "UA-x", "203.0.113.9",
                null, "mobile", "BR"), MongoCollections.CLICK_EVENTS);

        rollupJob.rollupDay(yesterday);

        ClickDailyDocument row = findRow("aaa111", yesterday.toString());
        assertThat(row).isNotNull();
        assertThat(row.getClicks()).isEqualTo(3);
        assertThat(row.getUniqueDays()).isEqualTo(1);
        assertThat(row.getBreakdown().get("device"))
                .containsEntry("mobile", 2L).containsEntry("desktop", 1L);
        assertThat(row.getBreakdown().get("country"))
                .containsEntry("BR", 1L).containsEntry("US", 1L).containsEntry("(none)", 1L);
        assertThat(row.getBreakdown().get("referrer"))
                .containsEntry("https://ref.example.com", 2L).containsEntry("(none)", 1L);
    }

    @Test
    @DisplayName("Re-running the rollup for the same day is idempotent")
    void rerunIsIdempotent() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Instant start = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant();
        mongoTemplate.insert(new ClickEventDocument("bbb222", start, "UA-1", "203.0.113.1",
                null, "mobile", null), MongoCollections.CLICK_EVENTS);

        rollupJob.rollupDay(yesterday);
        rollupJob.rollupDay(yesterday);

        List<ClickDailyDocument> rows = mongoTemplate.find(
                Query.query(Criteria.where("shortCode").is("bbb222")),
                ClickDailyDocument.class);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getClicks()).isEqualTo(1);
    }

    private ClickDailyDocument findRow(String shortCode, String day) {
        return mongoTemplate.findOne(
                Query.query(Criteria.where("shortCode").is(shortCode).and("day").is(day)),
                ClickDailyDocument.class);
    }
}