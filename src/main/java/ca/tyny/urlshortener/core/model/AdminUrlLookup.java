package ca.tyny.urlshortener.core.model;

/**
 * Result of an admin lookup of a short URL by its code (the code is the document id).
 *
 * <p>Carries the short URL itself plus the owner email resolved at lookup time. The owner document
 * may no longer exist (e.g. a deleted account) — in that case {@code ownerEmail} is {@code null};
 * the {@code ownerUserId} is always available on the short URL record itself. The nullable contract
 * is documented in the OpenAPI spec.
 */
public record AdminUrlLookup(ShortUrl shortUrl, String ownerEmail) {}
