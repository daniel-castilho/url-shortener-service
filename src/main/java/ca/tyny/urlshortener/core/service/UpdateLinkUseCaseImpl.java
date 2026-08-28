package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.command.UpdateLinkCommand;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidExpiryException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.incoming.UpdateLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.validation.UrlValidator;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class UpdateLinkUseCaseImpl implements UpdateLinkUseCase {

    private final LinkQueryPort linkQueryPort;
    private final LinkMutationPort linkMutationPort;
    private final UrlValidator urlValidator;
    private final long maxTtlSeconds;

    public UpdateLinkUseCaseImpl(LinkQueryPort linkQueryPort,
                                 LinkMutationPort linkMutationPort,
                                 UrlValidator urlValidator,
                                 long maxTtlSeconds) {
        this.linkQueryPort = linkQueryPort;
        this.linkMutationPort = linkMutationPort;
        this.urlValidator = urlValidator;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    @Override
    public ShortUrl update(String userId, String id, UpdateLinkCommand command)
            throws UrlNotFoundException, ForbiddenException, IllegalArgumentException, InvalidExpiryException {

        ShortUrl shortUrl = linkQueryPort.findById(id)
                .orElseThrow(() -> new UrlNotFoundException(id));

        if (!shortUrl.userId().equals(userId)) {
            throw new ForbiddenException("User does not own this link");
        }

        if (shortUrl.deletedAt() != null) {
            throw new IllegalArgumentException("Cannot update an archived link");
        }

        Instant expiresAt = command.expiresAt();

        if (command.originalUrl() != null) {
            urlValidator.validate(command.originalUrl());
        }

        List<String> tags = command.tags();
        if (tags != null) {
            if (tags.size() > 20) {
                throw new IllegalArgumentException("Maximum 20 tags allowed");
            }
            tags = tags.stream()
                    .map(String::toLowerCase)
                    .distinct()
                    .collect(Collectors.toList());
            for (String tag : tags) {
                if (tag.length() < 1 || tag.length() > 50 || !tag.matches("[a-z0-9_-]+")) {
                    throw new IllegalArgumentException("Tags must be 1-50 chars, alphanumeric/underscore/hyphen only");
                }
            }
        }

        ShortUrl updated = shortUrl
                .withOriginalUrl(command.originalUrl() != null ? command.originalUrl() : shortUrl.originalUrl())
                .withTitle(command.title())
                .withTags(tags != null ? tags : shortUrl.tags())
                .withUtm(command.utm() != null ? command.utm() : shortUrl.utm())
                .withExpiresAt(expiresAt);

        linkMutationPort.update(updated);
        return updated;
    }
}