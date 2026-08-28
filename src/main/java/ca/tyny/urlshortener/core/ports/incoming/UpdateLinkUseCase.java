package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.command.UpdateLinkCommand;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidExpiryException;

/**
 * Use case for partially updating a short link.
 * Only the fields present in the command are updated.
 */
public interface UpdateLinkUseCase {

    /**
     * Updates a link's metadata and/or destination.
     *
     * @param userId the authenticated user's ID
     * @param id the short URL code
     * @param command partial update command (only supplied fields change)
     * @return the updated link
     * @throws UrlNotFoundException if the link does not exist
     * @throws ForbiddenException if the user is not the owner
     * @throws IllegalArgumentException if the link is archived (immutable)
     * @throws InvalidExpiryException if expiresAt exceeds the server cap
     */
    ShortUrl update(String userId, String id, UpdateLinkCommand command)
            throws UrlNotFoundException, ForbiddenException, IllegalArgumentException, InvalidExpiryException;
}