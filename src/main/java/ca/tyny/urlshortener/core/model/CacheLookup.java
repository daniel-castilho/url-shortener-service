package ca.tyny.urlshortener.core.model;

/**
 * Result of a cache lookup that distinguishes between:
 * - a cache hit (value present),
 * - a cache miss (not in cache, may exist in DB),
 * - a bloom-negative (Bloom filter says the code almost certainly does not exist).
 *
 * Under Policy B, a {@code BLOOM_NEGATIVE} is treated as a lightweight cache-miss and
 * resolved by {@code findById}; the Bloom filter short-circuits only the Redis {@code get},
 * not the MongoDB lookup. This is the intended behaviour.
 */
public record CacheLookup(CachedUrlValue value, Absence absence) {

    public enum Absence {
        /** Value is present (cache hit). */
        NONE,
        /** Not in cache, may exist in DB (classic cache miss). */
        MISS,
        /** Bloom filter says the code almost certainly does not exist.
         * Under Policy B this is treated as a lightweight cache-miss and resolved by {@code findById}.
         * The Bloom filter short-circuits only the Redis {@code get}, not the MongoDB lookup. */
        BLOOM_NEGATIVE
    }

    public static CacheLookup hit(CachedUrlValue value) {
        return new CacheLookup(value, Absence.NONE);
    }

    public static CacheLookup miss() {
        return new CacheLookup(null, Absence.MISS);
    }

    public static CacheLookup bloomNegative() {
        return new CacheLookup(null, Absence.BLOOM_NEGATIVE);
    }

    public boolean isHit() {
        return value() != null && absence() == Absence.NONE;
    }
}