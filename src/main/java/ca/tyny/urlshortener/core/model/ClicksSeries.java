package ca.tyny.urlshortener.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Time-series click statistics for a single short URL, owner-guarded at the use-case layer.
 *
 * @param totalClicks sum across the requested range
 * @param series ordered buckets (daily or hourly, ascending)
 * @param breakdown optional dimension (device | country | referrer) to value-to-count map for the
 *     range, from the click_daily rollup
 */
public record ClicksSeries(
    String shortCode,
    String unit,
    Instant from,
    Instant to,
    long totalClicks,
    List<Bucket> series,
    Map<String, Map<String, Long>> breakdown,
    Map<String, Long> uniquePerBucket) {

  public record Bucket(Instant time, long clicks) {}
}
