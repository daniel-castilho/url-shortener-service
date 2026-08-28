package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;

/**
 * Use case for listing the authenticated user's short links.
 * Returns a cursor-paginated page of links, newest first.
 */
public interface ListUserLinksUseCase {

    PageResult<ShortUrl> list(String userId, PageRequest request);
}