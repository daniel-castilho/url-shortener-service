package ca.tyny.urlshortener.core.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Opaque cursor for stable pagination.
 * <p>
 * Encodes {@code <epochMillis>:<id>} as Base64url. The client treats it as opaque;
 * the server decodes and validates. Ordering is {@code createdAt} DESC with
 * {@code _id} as tiebreaker (also DESC) to avoid skipping/duplicating when
 * multiple links share the same {@code createdAt}.
 */
public record Cursor(String value) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * Creates a cursor from creation timestamp and ID.
     *
     * @param createdAt epoch milliseconds (UTC)
     * @param id        short code
     * @return opaque cursor string
     */
    public static Cursor of(long createdAt, String id) {
        String raw = createdAt + ":" + id;
        return new Cursor(ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Decodes the cursor into its components.
     *
     * @return tuple of [createdAtEpochMillis, id]
     * @throws IllegalArgumentException if the cursor is malformed
     */
    public long[] decode() {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cursor value cannot be blank");
        }
        try {
            String decoded = new String(DECODER.decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Cursor must contain exactly one ':' separator");
            }
            long createdAt = Long.parseLong(parts[0]);
            String id = parts[1];
            if (id.isBlank()) {
                throw new IllegalArgumentException("Cursor ID component cannot be empty");
            }
            return new long[]{createdAt, 0}; // second element unused, id returned separately
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed cursor: " + value, e);
        }
    }

    /**
     * Decodes the cursor and returns the createdAt epoch millis.
     */
    public long createdAtEpochMillis() {
        return decode()[0];
    }

    /**
     * Decodes the cursor and returns the ID.
     */
    public String id() {
        try {
            String decoded = new String(DECODER.decode(value), StandardCharsets.UTF_8);
            return decoded.split(":", 2)[1];
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed cursor: " + value, e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cursor)) return false;
        Cursor cursor = (Cursor) o;
        return Objects.equals(value, cursor.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}