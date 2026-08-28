package ca.tyny.urlshortener.core.model;

import java.util.List;

/**
 * Result of a cursor-based pagination query.
 *
 * @param <T> type of items in the page
 */
public record PageResult<T>(List<T> items, Cursor nextCursor, boolean hasMore) {

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), null, false);
    }

    public static <T> PageResult<T> of(List<T> items, Cursor nextCursor) {
        return new PageResult<>(items, nextCursor, nextCursor != null);
    }
}