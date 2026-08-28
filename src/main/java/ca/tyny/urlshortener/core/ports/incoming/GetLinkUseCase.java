package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;

/**
 * Use case for retrieving a link's details with ownership check.
 */
public interface GetLinkUseCase {

    /**
     * Returns the link if the user owns it.
     *
     * @param userId the authenticated user's ID
     * @param id the short URL code
     * @return the link with metadata and stats
     * @throws UrlNotFoundException if the link does not exist
     * @throws ForbiddenException if the user is not the owner
     */
    ShortUrl get(String userId, String id) throws UrlNotFoundException, ForbiddenException;
}