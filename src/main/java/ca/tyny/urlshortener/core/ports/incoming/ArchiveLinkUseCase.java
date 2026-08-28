package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;

/**
 * Use case for archiving (soft-deleting) a link.
 */
public interface ArchiveLinkUseCase {

    /**
     * Archives a link by setting its deletedAt timestamp.
     * Idempotent: re-archiving an already archived link is a no-op (returns normally).
     *
     * @param userId the authenticated user's ID
     * @param id the short URL code
     * @throws UrlNotFoundException if the link does not exist
     * @throws ForbiddenException if the user is not the owner
     */
    void archive(String userId, String id) throws UrlNotFoundException, ForbiddenException;
}