package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ClickAnalyticsResponse(
        String id,
        String unit,
        Instant from,
        Instant to,
        long totalClicks,
        List<ClickSeriesPoint> series,
        Map<String, Map<String, Long>> breakdown) {

    public record ClickSeriesPoint(Instant time, long clicks) {
    }
}