package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.incoming.ListUserLinksUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;

public class ListUserLinksUseCaseImpl implements ListUserLinksUseCase {

    private final LinkQueryPort linkQueryPort;

    public ListUserLinksUseCaseImpl(LinkQueryPort linkQueryPort) {
        this.linkQueryPort = linkQueryPort;
    }

    @Override
    public PageResult<ShortUrl> list(String userId, PageRequest request) {
        int limit = request.limit();
        Cursor cursor = request.cursor();
        return linkQueryPort.findByUserId(userId, limit, cursor);
    }
}