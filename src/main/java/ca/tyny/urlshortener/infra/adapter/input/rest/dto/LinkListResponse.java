package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import java.util.List;

public record LinkListResponse(
        List<ShortUrlResponse> items,
        String nextCursor,
        boolean hasMore) {
}