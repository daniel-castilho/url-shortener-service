package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.ShortUrl;

/**
 * Mutation port for writing short links.
 * <p>
 * Implemented by {@link ca.tyny.urlshortener.infra.adapter.output.persistence.MongoUrlRepository}.
 * Separated from {@link LinkQueryPort} to follow ISP and keep the redirect
 * path's {@link UrlRepositoryPort} unchanged.
 * <p>
 * A single adapter class ({@code MongoUrlRepository}) implements
 * {@link UrlRepositoryPort}, {@link LinkQueryPort}, and this interface.
 */
public interface LinkMutationPort {

    /**
     * Inserts or updates a short URL document (upsert by {@code _id}).
     * Used by the shortener flow and by updates.
     */
    void save(ShortUrl shortUrl);

    /**
     * Updates an existing short URL document by its {@code _id} (upsert).
     * Used by the update link use case.
     */
    void update(ShortUrl shortUrl);

    /**
     * Archives (soft-deletes) a link by setting {@code deletedAt} to now.
     * Idempotent: re-archiving an already archived link is a no-op.
     */
    void archive(String id);

    /**
     * Atomically increments the click count for a short URL by the given delta.
     * Used by the click pipeline worker.
     */
    void incrementClickCount(String id, long delta);
}