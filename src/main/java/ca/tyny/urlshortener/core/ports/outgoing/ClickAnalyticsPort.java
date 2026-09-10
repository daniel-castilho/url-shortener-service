package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.ClicksSeries;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Outbound port for read-only click statistics of a single short URL.
 *
 * <p>Implemented by the Mongo analytics adapter. Implementations must stay cheap: the daily series
 * and the breakdown read the {@code click_daily} rollup (never the raw event log); only the
 * explicitly requested hourly series touches raw {@code click_events}, and callers bound its range.
 */
public interface ClickAnalyticsPort {

  /** Ordered daily buckets (ascending) for {@code [from, to]} inclusive. */
  List<ClicksSeries.Bucket> daily(String shortCode, LocalDate from, LocalDate to);

  /**
   * Ordered hourly buckets (ascending) for the given instant range {@code [fromInclusive,
   * toExclusive)}.
   */
  List<ClicksSeries.Bucket> hourly(String shortCode, Instant fromInclusive, Instant toExclusive);

  /**
   * Per-dimension value-to-count breakdown for {@code [from, to]} inclusive, summed across days
   * from the rollup. Keyed by device | country | referrer.
   */
  Map<String, Map<String, Long>> breakdown(String shortCode, LocalDate from, LocalDate to);

  /**
   * Approximate unique visitor counts per UTC day for {@code [from, to]} inclusive, from Redis
   * HyperLogLog (one HLL per {@code (shortCode, day)}). Returns a map keyed by UTC day string
   * (yyyy-MM-dd) to unique count.
   */
  Map<String, Long> uniquePerDay(String shortCode, LocalDate from, LocalDate to);
}
