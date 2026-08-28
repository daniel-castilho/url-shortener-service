package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.incoming.GetLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;

public class GetLinkUseCaseImpl implements GetLinkUseCase {

    private final LinkQueryPort linkQueryPort;

    public GetLinkUseCaseImpl(LinkQueryPort linkQueryPort) {
        this.linkQueryPort = linkQueryPort;
    }

    @Override
    public ShortUrl get(String userId, String id) throws UrlNotFoundException, ForbiddenException {
        ShortUrl shortUrl = linkQueryPort.findById(id)
                .orElseThrow(() -> new UrlNotFoundException(id));

        if (!shortUrl.userId().equals(userId)) {
            throw new ForbiddenException("User does not own this link");
        }

        return shortUrl;
    }
}