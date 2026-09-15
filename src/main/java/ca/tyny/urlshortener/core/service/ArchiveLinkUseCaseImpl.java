package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.incoming.ArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class ArchiveLinkUseCaseImpl implements ArchiveLinkUseCase {

  private final LinkQueryPort linkQueryPort;
  private final LinkMutationPort linkMutationPort;
  private final UrlCachePort urlCachePort;
  private final UserRepositoryPort userRepository;

  public ArchiveLinkUseCaseImpl(
      LinkQueryPort linkQueryPort, LinkMutationPort linkMutationPort, UrlCachePort urlCachePort) {
    this(linkQueryPort, linkMutationPort, urlCachePort, null);
  }

  public ArchiveLinkUseCaseImpl(
      LinkQueryPort linkQueryPort,
      LinkMutationPort linkMutationPort,
      UrlCachePort urlCachePort,
      UserRepositoryPort userRepository) {
    this.linkQueryPort = linkQueryPort;
    this.linkMutationPort = linkMutationPort;
    this.urlCachePort = urlCachePort;
    this.userRepository = userRepository;
  }

  @Override
  public void archive(String userId, String id) throws UrlNotFoundException, ForbiddenException {
    ShortUrl shortUrl = linkQueryPort.findById(id).orElseThrow(() -> new UrlNotFoundException(id));

    if (!shortUrl.userId().equals(userId)) {
      throw new ForbiddenException("User does not own this link");
    }

    assertNotBlocked(userId);

    if (shortUrl.deletedAt() != null) {
      return;
    }

    linkMutationPort.archive(id);
    urlCachePort.evict(id);
  }

  private void assertNotBlocked(String userId) {
    if (userRepository != null) {
      userRepository
          .findById(userId)
          .filter(User::blocked)
          .ifPresent(
              u -> {
                throw new ForbiddenException("Account blocked.");
              });
    }
  }
}
