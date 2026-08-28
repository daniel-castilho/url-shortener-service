package ca.tyny.urlshortener.core.model;

/**
 * Granularity of a click statistics series.
 */
public enum AnalyticsUnit {

    /** One bucket per UTC calendar day, backed by the click_daily rollup. */
    DAY,

    /** One bucket per UTC hour, derived from raw click_events (bounded range). */
    HOUR;

    public static AnalyticsUnit fromParam(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        return switch (value.trim().toLowerCase()) {
            case "day" -> DAY;
            case "hour" -> HOUR;
            default -> throw new IllegalArgumentException(
                    "Invalid analytics unit '" + value + "'; expected 'day' or 'hour'");
        };
    }
}