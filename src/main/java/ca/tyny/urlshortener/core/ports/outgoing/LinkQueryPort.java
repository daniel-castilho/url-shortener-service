package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;

import java.util.List;
import java.util.Optional;

/**
 * Query port for reading short links owned by a user.
 * <p>
 * Implemented by {@link ca.tyny.urlshortener.infra.adapter.output.persistence.MongoUrlRepository}.
 * Separated from {@link LinkMutationPort} to follow ISP and keep the redirect
 * path's {@link UrlRepositoryPort} unchanged.
 */
public interface LinkQueryPort {

    /**
     * Returns a page of links owned by the given user, ordered by creation time
     * descending (newest first). Cursor-based pagination ensures stability under
     * concurrent inserts.
     *
     * @param userId the user identifier
     * @param limit   maximum number of items to return (capped at {@link ca.tyny.urlshortener.core.model.PageRequest#MAX_LIMIT})
     * @param cursor  opaque cursor for pagination; {@code null} for first page
     * @return page result with items, next cursor, and {@code hasMore} flag
     */
    PageResult<ShortUrl> findByUserId(String userId, int limit, Cursor cursor);

    /**
     * Finds a short URL by its code, regardless of ownership.
     * Used by the redirect path and ownership checks.
     */
    Optional<ShortUrl> findById(String id);
}