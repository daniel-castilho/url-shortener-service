package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.incoming.ArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;

public class ArchiveLinkUseCaseImpl implements ArchiveLinkUseCase {

    private final LinkQueryPort linkQueryPort;
    private final LinkMutationPort linkMutationPort;

    public ArchiveLinkUseCaseImpl(LinkQueryPort linkQueryPort, LinkMutationPort linkMutationPort) {
        this.linkQueryPort = linkQueryPort;
        this.linkMutationPort = linkMutationPort;
    }

    @Override
    public void archive(String userId, String id) throws UrlNotFoundException, ForbiddenException {
        ShortUrl shortUrl = linkQueryPort.findById(id)
                .orElseThrow(() -> new UrlNotFoundException(id));

        if (!shortUrl.userId().equals(userId)) {
            throw new ForbiddenException("User does not own this link");
        }

        if (shortUrl.deletedAt() != null) {
            return;
        }

        linkMutationPort.archive(id);
    }
}