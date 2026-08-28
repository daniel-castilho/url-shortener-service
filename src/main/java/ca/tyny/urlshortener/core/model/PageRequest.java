package ca.tyny.urlshortener.core.model;

/**
 * Request parameters for cursor-based pagination.
 * <p>
 * The cursor is an opaque token returned by a previous page request.
 * Limit is capped at {@value #MAX_LIMIT} to prevent excessive page sizes.
 */
public record PageRequest(int limit, Cursor cursor) {

    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 20;

    public PageRequest {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    public static PageRequest first(int limit) {
        return new PageRequest(Math.min(limit, MAX_LIMIT), null);
    }

    public static PageRequest first() {
        return new PageRequest(DEFAULT_LIMIT, null);
    }

    public static PageRequest of(int limit, Cursor cursor) {
        return new PageRequest(limit, cursor);
    }
}