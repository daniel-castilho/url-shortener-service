package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.core.model.ClicksSeries;
import ca.tyny.urlshortener.core.ports.outgoing.ClickAnalyticsPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.ClickDailyDocument;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * MongoDB adapter for read-only click statistics.
 *
 * <p>The daily series and the breakdown read the {@code click_daily} rollup — never the raw event
 * log. The hourly series aggregates raw {@code click_events} by UTC hour; callers bound the range
 * (see the use case).
 */
@Repository
public class MongoClickAnalyticsRepository implements ClickAnalyticsPort {

  private static final int MAX_HOURLY_BUCKETS = 2000;

  private final MongoTemplate mongoTemplate;
  private final StringRedisTemplate redisTemplate;

  public MongoClickAnalyticsRepository(
      MongoTemplate mongoTemplate, StringRedisTemplate redisTemplate) {
    this.mongoTemplate = mongoTemplate;
    this.redisTemplate = redisTemplate;
  }

  @Override
  public List<ClicksSeries.Bucket> daily(String shortCode, LocalDate from, LocalDate to) {
    List<ClickDailyDocument> rows =
        mongoTemplate.find(
            Query.query(Criteria.where("shortCode").is(shortCode))
                .addCriteria(Criteria.where("day").gte(from.toString()).lte(to.toString())),
            ClickDailyDocument.class);
    return rows.stream()
        .sorted((a, b) -> a.getDay().compareTo(b.getDay()))
        .map(
            row ->
                new ClicksSeries.Bucket(
                    LocalDate.parse(row.getDay()).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    row.getClicks()))
        .toList();
  }

  @Override
  public List<ClicksSeries.Bucket> hourly(
      String shortCode, Instant fromInclusive, Instant toExclusive) {
    List<Document> pipeline =
        List.of(
            new Document(
                "$match",
                new Document("shortCode", shortCode)
                    .append(
                        "timestamp",
                        new Document("$gte", fromInclusive).append("$lt", toExclusive))),
            new Document(
                "$group",
                new Document(
                        "_id",
                        new Document(
                            "$dateTrunc",
                            new Document("date", "$timestamp")
                                .append("unit", "hour")
                                .append("timezone", "UTC")
                                .append("binSize", 1)))
                    .append("clicks", new Document("$sum", 1))),
            new Document("$sort", new Document("_id", 1)),
            new Document("$limit", MAX_HOURLY_BUCKETS));

    List<Document> results =
        mongoTemplate
            .getMongoDatabaseFactory()
            .getMongoDatabase()
            .getCollection(MongoCollections.CLICK_EVENTS)
            .aggregate(pipeline)
            .into(new ArrayList<>());

    return results.stream()
        .map(
            doc ->
                new ClicksSeries.Bucket(
                    ((java.util.Date) doc.get("_id")).toInstant(),
                    ((Number) doc.get("clicks")).longValue()))
        .toList();
  }

  @Override
  public Map<String, Map<String, Long>> breakdown(String shortCode, LocalDate from, LocalDate to) {
    List<ClickDailyDocument> rows =
        mongoTemplate.find(
            Query.query(Criteria.where("shortCode").is(shortCode))
                .addCriteria(Criteria.where("day").gte(from.toString()).lte(to.toString())),
            ClickDailyDocument.class);

    Map<String, Map<String, Long>> merged = new LinkedHashMap<>();
    rows.forEach(
        row -> {
          if (row.getBreakdown() == null) {
            return;
          }
          row.getBreakdown()
              .forEach(
                  (dimension, values) ->
                      values.forEach(
                          (value, count) ->
                              merged
                                  .computeIfAbsent(dimension, d -> new LinkedHashMap<>())
                                  .merge(value, count, Long::sum)));
        });
    return merged.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  @Override
  public Map<String, Long> uniquePerDay(String shortCode, LocalDate from, LocalDate to) {
    Map<String, Long> unique = new LinkedHashMap<>();
    LocalDate day = from;
    while (!day.isAfter(to)) {
      String hllKey = "hll:clicks:" + shortCode + ":" + day;
      Long count = redisTemplate.opsForHyperLogLog().size(hllKey);
      unique.put(day.toString(), count != null ? count : 0L);
      day = day.plusDays(1);
    }
    return unique;
  }
}
